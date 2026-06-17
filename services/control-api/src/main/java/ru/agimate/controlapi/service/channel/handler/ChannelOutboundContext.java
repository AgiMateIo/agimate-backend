package ru.agimate.controlapi.service.channel.handler;

import java.util.UUID;

/**
 * Контекст исходящей доставки, передаваемый в {@link ChannelHandler#process}.
 *
 * <p>Несёт то, что нужно для регистрации вызова тула ({@code ToolUseLog}) и идемпотентности,
 * но не относится к содержимому ответа модели ({@link OutboundMessage}).
 *
 * @param agentId    агент-отправитель
 * @param userId     владелец канала
 * @param toolCallId tool_call_id от агента (для идемпотентности); может быть null
 */
public record ChannelOutboundContext(
        UUID agentId,
        UUID userId,
        String toolCallId
) {
}
