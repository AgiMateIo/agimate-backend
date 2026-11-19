package ru.agimate.controlapi.service.delivery;

import dev.dbos.transact.DBOSClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.agimate.agentworker.RunEvent;
import ru.agimate.agentworker.WorkerProtocol;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;

import java.util.Collection;
import java.util.UUID;

/**
 * Notifications to a run's workflow, sent over the DBOS message table so a waiting tool step wakes on
 * a fact instead of a timer. The bus is the system database both services already share: an insert
 * into {@code notifications} fires a {@code pg_notify} trigger, and {@code DBOS.recv} on the other
 * side returns the message even if it was sent before the wait began.
 *
 * <p>Every send is best-effort and goes out after the caller's transaction commits — a message about
 * a result that rolled back would be a lie, while a lost message costs the wait's reconciliation
 * tick, never the result. {@code dbos.enabled=false} (a local run without a worker) makes every call
 * a no-op.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunEvents {

    private final ObjectProvider<DBOSClient> clientProvider;
    private final AgentRunRepository agentRunRepository;

    /** A tool call of the run finished (or was detached): the wait may read its result now. */
    public void toolFinished(UUID runId, String toolCallId) {
        send(runId, RunEvent.toolFinished(toolCallId), toolCallId);
    }

    /** A new run appeared in the session: every running run of it has something to absorb. */
    public void inputArrived(UUID sessionId, UUID newRunId) {
        // A direct run has no session, hence no one to wake.
        if (sessionId == null) {
            return;
        }
        agentRunRepository.findRunningIdsBySession(sessionId)
                .forEach(runningId -> send(runningId, RunEvent.steerable(newRunId.toString()),
                        "input:" + newRunId));
    }

    /** The user asked to stop these runs: a waiting tool step ends now instead of at its next tick. */
    public void cancelRequested(Collection<UUID> runIds) {
        runIds.forEach(runId -> send(runId, RunEvent.cancelRequested(), "cancel:" + runId));
    }

    private void send(UUID runId, RunEvent event, String idempotencyKey) {
        DBOSClient client = clientProvider.getIfAvailable();
        if (client == null) {
            log.debug("run event {} for run {} skipped: dbos.enabled=false", event.kind(), runId);
            return;
        }
        afterCommit(() -> {
            try {
                client.send(runId.toString(), event, WorkerProtocol.RUN_EVENTS_TOPIC, idempotencyKey);
            } catch (Exception e) {
                log.warn("run event {} for run {} was not sent: {}", event.kind(), runId, e.getMessage());
            }
        });
    }

    /** After commit when there is one; immediately otherwise (a call outside a transaction). */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
