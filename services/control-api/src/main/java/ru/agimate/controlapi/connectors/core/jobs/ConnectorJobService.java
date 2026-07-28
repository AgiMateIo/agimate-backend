package ru.agimate.controlapi.connectors.core.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.database.entities.ConnectorJob;
import ru.agimate.controlapi.database.enums.ConnectorJobKind;
import ru.agimate.controlapi.database.enums.ConnectorJobStatus;
import ru.agimate.controlapi.database.repositories.ConnectorJobRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The write API for {@code connector_jobs}. It sits between the listeners/bootstrap and the database.
 *
 * <p>The pull model needs no events: the scheduler reads the database on every tick, so new and
 * deleted jobs enter or leave the working set automatically within one poll interval.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConnectorJobService {

    private static final int LAST_ERROR_LIMIT = 4_000;

    private final ConnectorJobRepository connectorJobRepository;

    /**
     * Not {@code readOnly} — the pickup inside performs an UPDATE ... RETURNING. The repo impl's
     * default {@code REQUIRED} propagation would join the outer readOnly transaction and fail at the
     * PG level.
     */
    @Transactional
    public List<ConnectorJob> claimReady(int batchSize) {
        return connectorJobRepository.claimReady(LocalDateTime.now(), batchSize);
    }

    /**
     * Creates or updates a row by the business key {@code (connectorCode, connectionId, name)}. A new
     * row gets {@code status=PENDING}, {@code next_run_at=now()} — the scheduler picks it up on the
     * next tick. A COMPLETED row (a finished ONETIME) is armed again.
     *
     * <p>{@code REQUIRES_NEW} is needed because the method is called from a
     * {@code @TransactionalEventListener(AFTER_COMMIT)} — there the outer transaction is already
     * committed, but its EntityManagerHolder is still bound to the thread. REQUIRED would participate
     * in a dead transaction and fail with «No active transaction». REQUIRES_NEW suspends the stale
     * holder and starts a clean tx.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConnectorJob upsert(String connectorCode, String connectionId, UUID userId, JobSpec spec) {
        return doUpsert(connectorCode, connectionId, userId, spec);
    }

    /**
     * Brings the set of SYSTEM jobs of a connectionId in line with the connector's declaration: an
     * upsert of every current one plus deletion of rows whose {@code name} is no longer returned by
     * {@code getJobs()}. Dynamic jobs (USER/AGENT) on that connectionId are left untouched by the
     * re-sync. {@code REQUIRES_NEW} for the same reason as in {@link #upsert}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void syncConnectionJobs(String connectorCode, String connectionId, UUID userId,
                             Collection<JobSpec> specs) {
        if (specs.isEmpty()) {
            connectorJobRepository.deleteSystemByConnectionId(connectorCode, connectionId);
            return;
        }
        for (JobSpec spec : specs) {
            doUpsert(connectorCode, connectionId, userId, spec);
        }
        connectorJobRepository.deleteStale(connectorCode, connectionId,
                specs.stream().map(JobSpec::name).toList());
    }

    /**
     * Deletes every row of a connectionId, dynamic ones (USER/AGENT) included — called when an
     * integration is deleted, at which point they are unexecutable without credentials anyway.
     * {@code REQUIRES_NEW} for the same reason as in {@link #upsert}: the call comes from an
     * AFTER_COMMIT listener.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteByConnectionId(String connectorCode, String connectionId) {
        return connectorJobRepository.deleteByConnectionId(connectorCode, connectionId);
    }

    /**
     * Finishes the current iteration: moves the row to {@code PENDING}, clears the lease and sets
     * {@code next_run_at}. {@code lastError == null} means success.
     */
    @Transactional
    public void complete(UUID taskId, LocalDateTime nextRunAt, String lastError) {
        connectorJobRepository.complete(taskId, nextRunAt, trimError(lastError));
    }

    /**
     * Startup re-sync of the existing SYSTEM rows against the connectors' declarations
     * ({@code getJobs()}): a change to {@code @Job} (interval, timeout, config) reaches the database
     * without recreating the connection, and rows whose names are no longer declared (a telegram mode
     * switch polling→webhook, say) are deleted. It creates no new rows — those are created by
     * connection lifecycle events. The spec is updated by a targeted UPDATE, because status and lease
     * are written concurrently by the scheduler (this node's and the neighbouring ones').
     */
    @Transactional
    public void resyncSystemJobs(Map<String, Map<String, JobSpec>> declaredByConnector) {
        for (ConnectorJob row : connectorJobRepository.findByKind(ConnectorJobKind.SYSTEM)) {
            Map<String, JobSpec> declared = declaredByConnector.get(row.getConnectorCode());
            if (declared == null) {
                log.warn("System job {}/{}/{}: no handler in registry — left as is",
                        row.getConnectorCode(), row.getConnectionId(), row.getName());
                continue;
            }
            JobSpec spec = declared.get(row.getName());
            if (spec == null) {
                connectorJobRepository.deleteById(row.getId());
                log.info("Removed undeclared system job {}/{}/{}",
                        row.getConnectorCode(), row.getConnectionId(), row.getName());
                continue;
            }
            connectorJobRepository.updateSpec(
                    row.getId(), spec.type(), spec.config(), spec.args(), spec.timeoutSeconds());
        }
    }

    /**
     * Returns a row claimed by this node to the queue when the application stops: PENDING, to run
     * right after the restart. RUNNING only — rows finalised in a race are left alone.
     */
    @Transactional
    public void release(UUID taskId) {
        connectorJobRepository.release(taskId, LocalDateTime.now());
    }

    /** Finalises a successfully finished ONETIME: {@code status=COMPLETED}, with no next run. */
    @Transactional
    public void markCompleted(UUID taskId, String lastError) {
        connectorJobRepository.markCompleted(taskId, trimError(lastError));
    }

    // ===== Dynamic jobs scheduled by an agent (time.schedule and the like) =====

    /**
     * Schedules an agent's dynamic job ({@code kind=AGENT}): an INSERT of a new row (unlike
     * {@link #upsert} — the business key does not apply to it, and one agent may have many).
     * {@code firstRunAt} is the moment of the first firing (for a ONETIME that is the only run).
     */
    @Transactional
    public ConnectorJob schedule(String connectorCode, String connectionId, UUID userId, UUID agentId,
                                 UUID channelId, UUID sessionId, JobSpec spec, LocalDateTime firstRunAt) {
        if (agentId == null) {
            throw new ConnectorException("Dynamic task requires an initiating agent");
        }
        ConnectorJob row = ConnectorJob.builder()
                .connectorCode(connectorCode)
                .connectionId(connectionId)
                .userId(userId)
                .agentId(agentId)
                .channelId(channelId)
                .sessionId(sessionId)
                .kind(ConnectorJobKind.AGENT)
                .name(spec.name())
                .type(spec.type())
                .config(spec.config())
                .args(spec.args())
                .timeoutSeconds(spec.timeoutSeconds())
                .status(ConnectorJobStatus.PENDING)
                .nextRunAt(firstRunAt)
                .build();
        return connectorJobRepository.save(row);
    }

    /** An agent's active (non-COMPLETED) jobs — for list. */
    public List<ConnectorJob> findActiveByAgent(String connectorCode, UUID userId, UUID agentId) {
        return connectorJobRepository.findActiveByAgent(connectorCode, userId, agentId);
    }

    /** Cancels an agent's job with an owner check; {@code true} means it really was deleted. */
    @Transactional
    public boolean cancel(String connectorCode, UUID userId, UUID agentId, UUID taskId) {
        return connectorJobRepository.deleteOwned(taskId, connectorCode, userId, agentId) > 0;
    }

    private ConnectorJob doUpsert(String connectorCode, String connectionId, UUID userId, JobSpec spec) {
        ConnectorJob row = connectorJobRepository.findByBusinessKey(connectorCode, connectionId, spec.name())
                .orElseGet(() -> ConnectorJob.builder()
                        .connectorCode(connectorCode)
                        .connectionId(connectionId)
                        .kind(ConnectorJobKind.SYSTEM)
                        .name(spec.name())
                        .status(ConnectorJobStatus.PENDING)
                        .nextRunAt(LocalDateTime.now())
                        .build());

        if (row.getStatus() == ConnectorJobStatus.COMPLETED) {
            row.setStatus(ConnectorJobStatus.PENDING);
            row.setNextRunAt(LocalDateTime.now());
        }
        row.setUserId(userId);
        row.setType(spec.type());
        row.setConfig(spec.config());
        row.setArgs(spec.args());
        row.setTimeoutSeconds(spec.timeoutSeconds());

        return connectorJobRepository.save(row);
    }

    private static String trimError(String lastError) {
        return (lastError == null || lastError.length() <= LAST_ERROR_LIMIT)
                ? lastError
                : lastError.substring(0, LAST_ERROR_LIMIT);
    }
}
