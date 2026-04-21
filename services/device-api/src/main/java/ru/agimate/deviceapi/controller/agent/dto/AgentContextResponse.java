package ru.agimate.deviceapi.controller.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Agent context: self, team, teammates")
public record AgentContextResponse(
        @Schema(description = "Information about the current agent")
        Self self,

        @Schema(description = "Agentic team the agent belongs to (null if none)")
        Team team,

        @Schema(description = "All agents in the same team (including self); empty if no team")
        List<TeamAgent> teamAgents
) {

    @Schema(description = "Current agent info")
    public record Self(
            @Schema(description = "Agent public ID") UUID pubId,
            @Schema(description = "Agent name") String name,
            @Schema(description = "Agent description") String description,
            @Schema(description = "Agent prompt") String prompt
    ) {}

    @Schema(description = "Agentic team info")
    public record Team(
            @Schema(description = "Team public ID") UUID pubId,
            @Schema(description = "Team name") String name,
            @Schema(description = "Team description") String description
    ) {}

    @Schema(description = "Team agent short info")
    public record TeamAgent(
            @Schema(description = "Agent public ID") UUID pubId,
            @Schema(description = "Agent name") String name,
            @Schema(description = "Agent description") String description
    ) {}
}
