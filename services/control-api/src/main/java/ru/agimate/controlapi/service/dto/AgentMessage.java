package ru.agimate.controlapi.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.trigger.Channels;

/**
 * A message to the worker. For channel triggers it carries {@link Channels} (where to build the
 * interaction) and the {@link InboundMessage} control-api has already extracted (what the user said);
 * for direct triggers both fields are {@code null}. {@code sessionId} is the run's
 * single-writer/history key, resolved once in {@code TriggerRouterService} (the prompt channel,
 * otherwise the answer one) — the worker does not derive it from the channels itself. {@code NON_NULL}
 * strips unpopulated fields from the payload.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentMessage<T>(
        String agentId,
        String runId,
        String type,
        String sessionId,
        Channels channels,
        InboundMessage inbound,
        T payload
) {
}
