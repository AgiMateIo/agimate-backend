package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.Agent;

import java.util.UUID;

@Schema(description = "Lightweight agent summary")
public record AgentSummaryResponse(
        @Schema(description = "Agent ID")
        UUID id,

        @Schema(description = "Agent name")
        String name,

        @Schema(description = "Agent description")
        String description,

        @Schema(description = "Agent prompt")
        String prompt,

        @Schema(description = "Whether the agent is enabled")
        boolean enabled
) {
    public static AgentSummaryResponse from(Agent agent) {
        return new AgentSummaryResponse(
                agent.getPubId(),
                agent.getName(),
                agent.getDescription(),
                agent.getPrompt(),
                agent.isEnabled()
        );
    }
}
