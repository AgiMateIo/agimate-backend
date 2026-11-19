package ru.agimate.agentworker.workers.run;

import ru.agimate.agentworker.RunEvent;

import java.util.Map;

/**
 * Reads a run event out of what {@code DBOS.recv} returned. The DBOS java serializer writes a
 * record as a plain JSON object and, because a record is final, adds no type id — so the receiver
 * gets a {@code Map}, not the {@link RunEvent} the sender handed over. Fields are matched by name;
 * a payload that is not a map at all is treated as no event, never as a failure.
 */
final class RunEventPayload {

    private RunEventPayload() {
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
}
