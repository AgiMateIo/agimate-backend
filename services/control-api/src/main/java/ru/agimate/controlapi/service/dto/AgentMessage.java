package ru.agimate.controlapi.service.dto;

import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.trigger.Channels;

/**
 * Сообщение воркеру. Для канальных триггеров несёт {@link Channels} (куда строить взаимодействие)
 * и уже извлечённый control-api {@link InboundMessage} (что сказал пользователь); для прямых
 * триггеров оба поля {@code null}.
 */
public record AgentMessage<T>(
        String agentId,
        String runId,
        String type,
        Channels channels,
        InboundMessage inbound,
        T payload
) {
}
