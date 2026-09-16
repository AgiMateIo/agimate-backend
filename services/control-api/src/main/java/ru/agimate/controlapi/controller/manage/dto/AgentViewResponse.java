package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "A connector view available to the agent: a ui:// resource of one bound connection")
public record AgentViewResponse(
        @Schema(description = "Connection that serves the view")
        UUID connectionId,

        @Schema(description = "Connector code of the connection", example = "mcp")
        String connectorCode,

        @Schema(description = "Connection display name")
        String connectionName,

        @Schema(description = "View resource", example = "ui://server-everything/weather-dashboard")
        String uri,

        @Schema(description = "Tools of the connection that render into this view, allowed for the agent")
        List<String> tools
) {
}
