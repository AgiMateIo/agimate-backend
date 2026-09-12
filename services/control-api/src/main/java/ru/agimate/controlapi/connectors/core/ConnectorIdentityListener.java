package ru.agimate.controlapi.connectors.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.agimate.controlapi.connectors.core.events.ConnectorCreatedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorDeletedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorModifiedEvent;
import ru.agimate.controlapi.connectors.core.jobs.ConnectorJobService;

import java.util.UUID;

/**
 * Turns lifecycle events of connector instances into {@code connector_jobs} rows (the declaration is
 * {@link JobProvider#getJobs(ConnectorEnv)} for the instance at hand; a connector without
 * {@link JobProvider} has no jobs).
 *
 * <p>The pull model needs no notification to the scheduler — it will read the new or deleted row on
 * its next tick (≤1s). So the listener only writes to the database and publishes nothing back.
 *
 * <p>{@link TransactionPhase#AFTER_COMMIT} guarantees the row will not appear if the outer
 * transaction (creating the integration) rolled back. {@code fallbackExecution=true} allows handling
 * events published outside a transaction (tests).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorIdentityListener {

    private final ConnectorRegistry connectorRegistry;
    private final ConnectorJobService jobService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreated(ConnectorCreatedEvent event) {
        sync(event.connectorCode(), event.connectionId(), event.userId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onModified(ConnectorModifiedEvent event) {
        sync(event.connectorCode(), event.connectionId(), event.userId());
    }

    /**
     * Both events sync rather than «created» only adding: a re-authorisation publishes «created» for an
     * instance that already has rows, and its declaration may have shrunk since.
     */
    private void sync(String connectorCode, String connectionId, UUID userId) {
        connectorRegistry.declaredJobs(connectorCode, connectionId).ifPresentOrElse(
                declared -> {
                    jobService.syncConnectionJobs(connectorCode, connectionId, userId, declared.values());
                    log.info("Synced tasks for {}/{}: {}", connectorCode, connectionId, declared.keySet());
                },
                () -> log.warn("{}/{}: no handler in registry — skipping", connectorCode, connectionId));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDeleted(ConnectorDeletedEvent event) {
        int removed = jobService.deleteByConnectionId(event.connectorCode(), event.connectionId());
        if (removed > 0) {
            log.info("Removed {} task row(s) for {}/{}",
                    removed, event.connectorCode(), event.connectionId());
        }
    }
}
