package ru.agimate.controlapi.service.trigger;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Также wire-payload для воркера в {@code AgentMessage}; {@code NON_NULL} убирает routing-поля (context) когда пусты. */
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
     * Триггер от внешнего источника, приславшего собственные {@code id} и время события: они
     * проносятся в {@code TriggerLog.externalId}/{@code occurredAt} для корреляции. Fallback на
     * случайный id и {@code now()}, когда источник их не указал (оба поля запроса необязательны).
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
}
