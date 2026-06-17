package ru.agimate.controlapi.service.channel.handler.dto;

import ru.agimate.controlapi.service.channel.handler.ChannelHandler;

import java.util.UUID;

/**
 * Контекст исходящей доставки, передаваемый в {@link ChannelHandler#process}.
 *
 * <p>userId не нужен — {@link ru.agimate.controlapi.service.AgentToolUseService} выводит его из
 * агента; здесь только агент-отправитель и идемпотентный ключ вызова.
 *
 * @param agentId    агент-отправитель
 * @param toolCallId tool_call_id от агента (идемпотентность); может быть null
 */
public record ChannelOutboundContext(
        UUID agentId,
        String toolCallId
) {
}
