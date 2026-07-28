package ru.agimate.controlapi.service.trigger;

import com.fasterxml.jackson.annotation.JsonInclude;
import ru.agimate.controlapi.database.entities.TriggerLog;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Also the wire payload for the worker in {@code AgentMessage}; {@code NON_NULL} strips the routing fields (context) when empty. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Trigger(
        String connectorCode,
        String connectionId,
        String name,
        String id,
        Map<String, Object> data,
        String occurredAt,
        TriggerContext context
) {

    /** A copy of the trigger with {@code data} replaced (ingest materialisation of media: raw descriptors → parts). */
    public Trigger withData(Map<String, Object> newData) {
        return new Trigger(connectorCode, connectionId, name, id, newData, occurredAt, context);
    }

    public static Trigger createBasic(String connectorCode, String connectionId, String name, Map<String, Object> data) {
        return new Trigger(
                connectorCode,
                connectionId,
                name, UUID.randomUUID().toString(),
                data,
                Instant.now().toString(),
                null
        );
    }

    public static Trigger createDirected(String connectorCode, String connectionId, String name,
                                         Map<String, Object> data, TriggerContext context) {
        return new Trigger(
                connectorCode,
                connectionId,
                name, UUID.randomUUID().toString(),
                data,
                Instant.now().toString(),
                context
        );
    }

    /**
     * A trigger from an external source that supplied its own {@code id} and event time: those are
     * carried into {@code TriggerLog.externalId}/{@code occurredAt} for correlation. It falls back to a
     * random id and {@code now()} when the source gave neither (both request fields are optional).
     */
    public static Trigger fromSource(String connectorCode, String connectionId, String name, String id,
                                     Map<String, Object> data, Instant occurredAt) {
        return new Trigger(
                connectorCode,
                connectionId,
                name,
                (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString(),
                data,
                (occurredAt != null ? occurredAt : Instant.now()).toString(),
                null
        );
    }

    /**
     * Reconstructs a {@link Trigger} from the run's persistent log row ({@link TriggerLog}) — for
     * assembling the context and canonicalising the inbound message. {@code context} is not restored:
     * routing already happened when the event was first processed.
     */
    public static Trigger fromLog(TriggerLog log) {
        return new Trigger(
                log.getConnectorCode(),
                log.getConnectionId(),
                log.getName(),
                log.getExternalId(),
                log.getInput(),
                log.getOccurredAt() == null ? null : log.getOccurredAt().toString(),
                null);
    }
}
