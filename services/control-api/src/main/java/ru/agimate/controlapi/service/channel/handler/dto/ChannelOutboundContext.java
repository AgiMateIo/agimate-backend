package ru.agimate.controlapi.service.channel.handler.dto;

import ru.agimate.controlapi.service.channel.handler.ChannelHandler;
import ru.agimate.controlapi.service.tool.AgentToolUseService;

import java.util.UUID;

/**
 * Контекст исходящей доставки, передаваемый в {@link ChannelHandler#handleOutput}.
 *
 * <p>userId не нужен — {@link AgentToolUseService} выводит его из
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
