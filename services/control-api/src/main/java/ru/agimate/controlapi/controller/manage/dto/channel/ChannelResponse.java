package ru.agimate.controlapi.controller.manage.dto.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.Channel;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Channel: a handler-driven binding between triggers and reply tools")
public record ChannelResponse(
        UUID id,
        UUID agentId,
        String name,
        String channelHandler,
        String connectorCode,
        String connectionId,
        @Schema(description = "Denormalized display name of the connection (Connection.name); null if missing or deleted")
        String connectionName,
        Map<String, Object> config,
        @Schema(description = "Optional input filter stored on the Channel; null if no filter is configured")
        Map<String, Object> inputFilter,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ChannelResponse from(Channel c, String connectionName, Map<String, Object> inputFilter) {
        return new ChannelResponse(
                c.getId(),
                c.getAgentId(),
                c.getName(),
                c.getChannelHandler(),
                c.getConnectorCode(),
                c.getConnectionId() == null ? null : c.getConnectionId().toString(),
                connectionName,
                c.getConfig(),
                inputFilter,
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }

    public static ChannelResponse from(Channel c) {
        return from(c, null, null);
    }
}
