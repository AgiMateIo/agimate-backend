package ru.agimate.controlapi.service.channel.handler.dto;

import ru.agimate.controlapi.service.channel.handler.ChannelHandler;

/**
 * Тул коннектора, который {@link ChannelHandler} может вызвать на исходящих.
 *
 * <p>В отличие от триггеров (всегда на connector+connectionId канала), reply-тул может бить в другой
 * коннектор/connectionId (для {@code generic} reply-цель лежит в config), поэтому ref полный.
 * Используется для генерации {@code AgentToolPolicy}.
 */
public record ToolDefinition(
        String connectorCode,
        String connectionId,
        String toolName
) {
}
