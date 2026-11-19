package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.DBOS;
import ru.agimate.agentworker.RunEvent;
import ru.agimate.agentworker.WorkerProtocol;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * The read side of a run's event topic: what a {@code DBOS.recv} returned, and how to empty the
 * topic. The DBOS java serializer writes a record as a plain JSON object and, because a record is
 * final, adds no type id — so the receiver gets a {@code Map}, not the {@link RunEvent} the sender
 * handed over. Fields are matched by name; a payload that is not a map at all is no event, never a
 * failure.
 *
 * <p>Draining is what keeps a stale message from deciding anything: every {@code recv} reads the
 * notification table directly before it waits, so a peek of a few milliseconds reliably finds what
 * is already queued.
 */
final class RunEventInbox {

    /** A peek must not wait — a queued message is found by the direct read that starts every recv. */
    private static final long PEEK_MS = 10;
    /** Ceiling on one drain: a storm must cost bounded checkpoints, and leftovers are re-read anyway. */
    private static final int MAX_DRAIN = 64;

    private RunEventInbox() {
    }

    static boolean isSteerable(Object payload) {
        return RunEvent.STEERABLE.equals(kind(payload));
    }

    /** @return the event kind, or {@code null} when the payload carries none */
    static String kind(Object payload) {
        if (payload instanceof Map<?, ?> fields && fields.get("kind") instanceof String kind) {
            return kind;
        }
        return null;
    }

    /**
     * Consume everything the run's topic holds right now and return how many were dropped. Each
     * peek is a durable step of the workflow, so the count is capped.
     */
    static int drain(DBOS dbos) {
        int dropped = 0;
        while (dropped < MAX_DRAIN) {
            Optional<Object> message = dbos.recv(WorkerProtocol.RUN_EVENTS_TOPIC, Duration.ofMillis(PEEK_MS));
            if (message.isEmpty()) {
                break;
            }
            dropped++;
        }
        return dropped;
    }
}
