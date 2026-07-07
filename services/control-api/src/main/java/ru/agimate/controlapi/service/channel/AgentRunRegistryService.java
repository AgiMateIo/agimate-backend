package ru.agimate.controlapi.service.channel;

import dev.dbos.transact.DBOSClient;
import dev.dbos.transact.workflow.ListWorkflowsInput;
import dev.dbos.transact.workflow.WorkflowStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.entities.TriggerLogAgent;
import ru.agimate.controlapi.database.repositories.TriggerLogAgentRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Active-run registry (AgentRunRegistry) over {@link TriggerLogAgent}.
 * A run row is created by the backend at trigger routing (status ENQUEUED);
 * the worker transitions it RUNNING on start (register) and DONE on finish (release).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentRunRegistryService {

    /** Default TTL backstop on a dead run; covers worst-case run duration. */
    static final int DEFAULT_TTL_SECONDS = 3600;

    private final TriggerLogAgentRepository triggerLogAgentRepository;
    private final ObjectProvider<DBOSClient> dbosClientProvider;

    /** Immutable view of the active run, materialized inside the transaction. */
    public record ActiveRunView(
            UUID agentId,
            UUID sessionId,
            UUID runId,
            LocalDateTime acquiredAt,
            LocalDateTime expiresAt
    ) {}

    /**
     * Atomic claim of the session slot: {@code markRunning} flips the run to RUNNING only when no
     * other RUNNING holder exists ({@code NOT EXISTS} inside the statement) — a busy slot is a
     * regular outcome, not an exception.
     *
     * @return the active run view, or {@link Optional#empty()} when another run holds the slot
     * @throws NotFoundStatusException when the run row is missing or already terminal
     */
    @Transactional
    public Optional<ActiveRunView> registerRun(UUID sessionId, UUID runId, int ttlSeconds) {
        int ttl = ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS;
        LocalDateTime acquiredAt = LocalDateTime.now();
        LocalDateTime expiresAt = acquiredAt.plusSeconds(ttl);

        int updated = triggerLogAgentRepository.markRunning(runId, sessionId, expiresAt, acquiredAt);
        if (updated == 0) {
            // Either the run row is gone/terminal (late replay after finish) or the slot is held.
            triggerLogAgentRepository.findById(runId)
                    .filter(r -> r.getStatus() == RunStatus.ENQUEUED || r.getStatus() == RunStatus.RUNNING)
                    .orElseThrow(() -> new NotFoundStatusException("Run not found: " + runId));
            return Optional.empty();
        }

        TriggerLogAgent run = triggerLogAgentRepository.findById(runId)
                .orElseThrow(() -> new NotFoundStatusException("Run not found: " + runId));
        log.debug("RegisterRun session={} run={} agent={} expiresAt={}",
                sessionId, runId, run.getAgent().getId(), expiresAt);
        return Optional.of(toView(run));
    }

    public Optional<ActiveRunView> getActiveRun(UUID sessionId) {
        return triggerLogAgentRepository
                .findActiveBySession(sessionId, LocalDateTime.now())
                .map(this::toView);
    }

    @Transactional
    public boolean releaseRun(UUID runId) {
        boolean released = triggerLogAgentRepository.releaseOwn(runId, RunStatus.DONE) == 1;
        log.debug("ReleaseRun run={} released={}", runId, released);
        return released;
    }

    /**
     * Evicts the session slot's holder if the run provably no longer needs it:
     * <ul>
     *   <li>the lease expired — the partial unique index ignores {@code expires_at}, so an
     *       expired RUNNING row still blocks the claim until evicted here (no sweeper);</li>
     *   <li>its DBOS workflow is dead ({@code run_id} == the run-stage workflow id): a run that
     *       errored during a control-api outage never released the slot, and would otherwise
     *       block the session until the TTL backstop. A missing workflow record also counts as
     *       dead — RegisterRun is issued from inside that workflow, so a RUNNING row implies the
     *       record once existed.</li>
     * </ul>
     *
     * @return {@code true} when the slot is free now (evicted, or already released concurrently)
     */
    @Transactional
    public boolean reclaimDeadHolder(UUID sessionId) {
        Optional<TriggerLogAgent> holder =
                triggerLogAgentRepository.findBySessionIdAndStatus(sessionId, RunStatus.RUNNING);
        if (holder.isEmpty()) {
            return true;
        }
        UUID holderRunId = holder.get().getId();
        LocalDateTime expiresAt = holder.get().getExpiresAt();
        String deadReason;
        if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now())) {
            deadReason = "TTL expired at " + expiresAt;
        } else {
            DBOSClient client = dbosClientProvider.getIfAvailable();
            if (client == null) {
                return false;
            }
            // listWorkflows, not getWorkflowStatus: the latter eagerly deserializes the workflow's
            // input/output, which are Java-serialized with worker-only classes (AgentMessage, ...)
            // absent from this classpath. loadInput/loadOutput=false fetches the bare status row.
            Optional<WorkflowStatus> status = client.listWorkflows(new ListWorkflowsInput()
                            .withWorkflowIds(holderRunId.toString())
                            .withLoadInput(false)
                            .withLoadOutput(false))
                    .stream().findFirst();
            if (status.isPresent() && status.get().status().isActive()) {
                return false;
            }
            deadReason = "workflow status " + status.map(s -> s.status().name()).orElse("NOT_FOUND");
        }
        boolean evicted = triggerLogAgentRepository.releaseOwn(holderRunId, RunStatus.FAILED) == 1;
        log.warn("Reclaimed session {} slot from dead run {} ({}, evicted={})",
                sessionId, holderRunId, deadReason, evicted);
        return true;
    }

    private ActiveRunView toView(TriggerLogAgent run) {
        return new ActiveRunView(
                run.getAgent().getId(),
                run.getSessionId(),
                run.getId(),
                run.getUpdatedAt(),
                run.getExpiresAt()
        );
    }
}
