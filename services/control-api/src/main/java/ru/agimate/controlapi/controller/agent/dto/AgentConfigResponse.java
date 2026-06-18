package ru.agimate.controlapi.controller.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Agent configuration")
public record AgentConfigResponse(
        @Schema(description = "Agent public ID")
        UUID agentId,

        @Schema(description = "Agent instructions")
        String instructions,

        @Schema(description = "Authorized tools with definitions")
        List<ToolDefinition> tools,

        @Schema(description = "Subscribed trigger names")
        List<String> triggers
) {
}
