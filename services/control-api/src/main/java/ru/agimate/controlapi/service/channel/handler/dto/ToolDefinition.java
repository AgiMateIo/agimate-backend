package ru.agimate.controlapi.service.channel.handler.dto;

import ru.agimate.controlapi.service.channel.handler.ChannelHandler;

/**
 * Тул коннектора, который {@link ChannelHandler} может вызвать на исходящих.
 *
 * <p>Reply-тул может бить в другой экземпляр (для {@code generic} reply-цель лежит в config).
 * Коннектор reply-цели выводится из {@code connectionId} ({@code connections.connector_code}),
 * поэтому в ref не дублируется.
 */
public record ToolDefinition(
        String connectionId,
        String toolName
) {
}
