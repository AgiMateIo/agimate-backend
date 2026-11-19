package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.DBOS;
import ru.agimate.agentworker.RunEvent;
import ru.agimate.agentworker.WorkerProtocol;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * The read side of a run's event topic: what a {@code DBOS.recv} returned, and how to empty the
 * topic. The payload comes back as the {@link RunEvent} the sender handed over — the DBOS java
 * serializer writes a type id for every non-primitive type ({@code useForType = !isPrimitive}), so
 * a record is restored as itself, not as a map. The map branch below is the fallback for a
 * serializer that strips type ids; a payload that is neither is no event, never a failure.
 *
 * <p>Draining is what keeps a burst from deciding one thing per message: every {@code recv} reads
 * the notification table directly before it waits, so a peek of a few milliseconds reliably finds
 * what is already queued.
 */
final class RunEventInbox {

    /** A peek must not wait — a queued message is found by the direct read that starts every recv. */
    private static final long PEEK_MS = 10;
    /** Ceiling on one drain: a storm must cost bounded checkpoints, and leftovers are re-read anyway. */
    private static final int MAX_DRAIN = 64;

    /**
     * @param count     messages consumed
     * @param steerable whether any of them said «an input is waiting» — the reason a drained burst
     *                  still has to be verified with the backend
     */
    record Drained(int count, boolean steerable) {}

    private RunEventInbox() {
    }

    static boolean isSteerable(Object payload) {
        return RunEvent.STEERABLE.equals(kind(payload));
    }

    /** @return the event kind, or {@code null} when the payload carries none */
    static String kind(Object payload) {
        if (payload instanceof RunEvent event) {
            return event.kind();
        }
        if (payload instanceof Map<?, ?> fields && fields.get("kind") instanceof String kind) {
            return kind;
        }
        return null;
    }

    /**
     * Consume everything the run's topic holds right now. Each peek is a durable step of the
     * workflow, so the number of rounds is capped; whatever is left is read by the next wait.
     */
    static Drained drain(DBOS dbos) {
        int count = 0;
        boolean steerable = false;
        while (count < MAX_DRAIN) {
            Optional<Object> message = dbos.recv(WorkerProtocol.RUN_EVENTS_TOPIC, Duration.ofMillis(PEEK_MS));
            if (message.isEmpty()) {
                break;
            }
            count++;
            steerable |= isSteerable(message.get());
        }
        return new Drained(count, steerable);
    }
}
