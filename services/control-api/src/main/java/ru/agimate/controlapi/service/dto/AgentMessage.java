package ru.agimate.controlapi.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.trigger.Channels;

/**
 * Сообщение воркеру. Для канальных триггеров несёт {@link Channels} (куда строить взаимодействие)
 * и уже извлечённый control-api {@link InboundMessage} (что сказал пользователь); для прямых
 * триггеров оба поля {@code null}. {@code sessionId} — single-writer/history ключ запуска,
 * резолвится один раз в {@code TriggerRouterService} (prompt-канал, иначе answer) — воркер не
 * выводит его из каналов сам. {@code NON_NULL} убирает незаполненные поля из payload.
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
