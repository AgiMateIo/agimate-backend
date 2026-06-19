package ru.agimate.controlapi.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.trigger.Channels;

/**
 * Сообщение воркеру. Для канальных триггеров несёт {@link Channels} (куда строить взаимодействие)
 * и уже извлечённый control-api {@link InboundMessage} (что сказал пользователь); для прямых
 * триггеров оба поля {@code null}. {@code NON_NULL} убирает незаполненные поля из payload.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentMessage<T>(
        String agentId,
        String runId,
        String type,
        Channels channels,
        InboundMessage inbound,
        T payload
) {
}
