package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.Connection;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Connector connection (instance) details")
public record ConnectionResponse(
        @Schema(description = "Connection public ID (= connection_id downstream)")
        UUID id,

        @Schema(description = "Connector code")
        String connectorCode,

        @Schema(description = "Sub code — platform instance discriminator (e.g. bot username, MCP host)")
        String subCode,

        @Schema(description = "Full code — stable client handle (e.g. mcp_context7)")
        String fullCode,

        @Schema(description = "Connection name")
        String name,

        @Schema(description = "Whether the connection is enabled")
        Boolean enabled,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Last used timestamp")
        LocalDateTime lastUsedAt,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Creation timestamp")
        LocalDateTime createdAt
) {
    public static ConnectionResponse from(Connection c) {
        return new ConnectionResponse(
                c.getId(),
                c.getConnectorCode(),
                c.getSubCode(),
                c.getFullCode(),
                c.getName(),
                c.getEnabled(),
                c.getLastUsedAt(),
                c.getCreatedAt()
        );
    }
}
