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
        send(runId, RunEvent.toolFinished(toolCallId), "tool:" + runId + ":" + toolCallId);
    }

    /** A new run appeared in the session: every running run of it has something to absorb. */
    public void inputArrived(UUID sessionId, UUID newRunId) {
        // A direct run has no session, hence no one to wake.
        if (sessionId == null) {
            return;
        }
        // Both halves happen after commit: a notification must not be able to mark the caller's
        // transaction rollback-only, and the runs it addresses are the ones already committed.
        afterCommit(() -> {
            try {
                agentRunRepository.findRunningIdsBySession(sessionId)
                        .forEach(runningId -> deliver(runningId, RunEvent.steerable(newRunId.toString()),
                                "input:" + runningId + ":" + newRunId));
            } catch (Exception e) {
                log.warn("run events for session {} were not sent ({})",
                        sessionId, e.getClass().getSimpleName());
            }
        });
    }

    /** The user asked to stop these runs: a waiting tool step ends now instead of at its next tick. */
    public void cancelRequested(Collection<UUID> runIds) {
        runIds.forEach(runId -> send(runId, RunEvent.cancelRequested(), "cancel:" + runId));
    }

    /**
     * The idempotency key always names its own destination: DBOS keys a message by the key alone on
     * one of its paths, so a key that only carried a tool call id would let a run's message be
     * swallowed by another run's — the same call id is unique per run, not globally.
     */
    private void send(UUID runId, RunEvent event, String idempotencyKey) {
        afterCommit(() -> deliver(runId, event, idempotencyKey));
    }

    /** The send itself, best-effort: for a caller that is already past its commit. */
    private void deliver(UUID runId, RunEvent event, String idempotencyKey) {
        DBOSClient client = clientProvider.getIfAvailable();
        if (client == null) {
            log.debug("run event {} for run {} skipped: dbos.enabled=false", event.kind(), runId);
            return;
        }
        try {
            client.send(runId.toString(), event, WorkerProtocol.RUN_EVENTS_TOPIC, idempotencyKey);
        } catch (Exception e) {
            // Ids only: a JDBC message can carry SQL and its parameters.
            log.warn("run event {} for run {} was not sent ({})",
                    event.kind(), runId, e.getClass().getSimpleName());
        }
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
