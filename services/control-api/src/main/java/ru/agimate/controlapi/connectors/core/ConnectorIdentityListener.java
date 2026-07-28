package ru.agimate.controlapi.connectors.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.connectors.core.events.ConnectorCreatedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorDeletedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorModifiedEvent;
import ru.agimate.controlapi.connectors.core.jobs.ConnectorJobService;

import java.util.Map;

/**
 * Turns lifecycle events of connector instances into {@code connector_jobs} rows (the declaration is
 * {@link JobProvider#getJobs()}; a connector without {@link JobProvider} has no jobs).
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
        ConnectorHandler handler = connectorRegistry.findHandler(event.connectorCode()).orElse(null);
        if (handler == null) {
            log.warn("ConnectorCreatedEvent({}, {}): no handler in registry — skipping",
                    event.connectorCode(), event.connectionId());
            return;
        }
        for (JobSpec spec : declaredJobs(handler).values()) {
            jobService.upsert(event.connectorCode(), event.connectionId(), event.userId(), spec);
            log.info("Registered task {}/{}/{}", event.connectorCode(), event.connectionId(), spec.name());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onModified(ConnectorModifiedEvent event) {
        ConnectorHandler handler = connectorRegistry.findHandler(event.connectorCode()).orElse(null);
        if (handler == null) {
            log.warn("ConnectorModifiedEvent({}, {}): no handler in registry — skipping",
                    event.connectorCode(), event.connectionId());
            return;
        }
        jobService.syncConnectionJobs(event.connectorCode(), event.connectionId(), event.userId(),
                declaredJobs(handler).values());
        log.info("Synced tasks for {}/{}", event.connectorCode(), event.connectionId());
    }

    private static Map<String, JobSpec> declaredJobs(ConnectorHandler handler) {
        return handler instanceof JobProvider jobProvider ? jobProvider.getJobs() : Map.of();
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
