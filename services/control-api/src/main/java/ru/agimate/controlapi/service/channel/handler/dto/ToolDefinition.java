package ru.agimate.controlapi.service.channel.handler.dto;

import ru.agimate.controlapi.service.channel.handler.ChannelHandler;

/**
 * A connector tool a {@link ChannelHandler} may call on outbound.
 *
 * <p>A reply tool may target a different instance (for {@code generic} the reply target lives in the
 * config). The reply target's connector is derived from {@code connectionId}
 * ({@code connections.connector_code}), so it is not duplicated in the ref.
 */
public record ToolDefinition(
        String connectionId,
        String toolName
) {
}
