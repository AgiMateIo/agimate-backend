package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.controller.manage.dto.llm.AgentLlmResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgenticTeam;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.service.AgentService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "Agent response")
public record AgentResponse(
        @Schema(description = "Agent ID")
        UUID id,

        @Schema(description = "Agent name")
        String name,

        @Schema(description = "Agent description")
        String description,

        @Schema(description = "Masked agent key ID")
        String maskedKeyId,

        @Schema(description = "Agent instructions")
        String instructions,

        @Schema(description = "Agent type")
        AgentType type,

        @Schema(description = "Webhook URL")
        String webhookUrl,

        @Schema(description = "Whether webhook auth header is configured")
        boolean hasWebhookAuth,

        @Schema(description = "Whether the agent is enabled")
        boolean enabled,

        @Schema(description = "Agentic team ID")
        UUID agenticTeamId,

        @Schema(description = "Agentic team name")
        String agenticTeamName,

        @Schema(description = "Skills bound to this agent")
        List<AgentSkillSummary> skills,

        @Schema(description = "LLM bindings (no api keys)")
        List<AgentLlmResponse> llms,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the agent was created")
        LocalDateTime createdAt
) {
    public static AgentResponse from(Agent agent,
                                     AgenticTeam team,
                                     List<AgentSkillSummary> skills,
                                     List<AgentLlmResponse> llms) {
        String maskedKeyId = AgentService.AGENT_KEY_PREFIX + agent.getKeyId().substring(0, 4) + "****";
        return new AgentResponse(
                agent.getId(),
                agent.getName(),
                agent.getDescription(),
                maskedKeyId,
                agent.getInstructions(),
                agent.getType(),
                agent.getWebhookUrl(),
                agent.hasWebhookAuth(),
                agent.isEnabled(),
                team != null ? team.getId() : null,
                team != null ? team.getName() : null,
                skills != null ? skills : List.of(),
                llms != null ? llms : List.of(),
                agent.getCreatedAt()
        );
    }

    public static AgentResponse from(Agent agent, AgenticTeam team, List<AgentSkillSummary> skills) {
        return from(agent, team, skills, List.of());
    }

    public static AgentResponse from(Agent agent, AgenticTeam team) {
        return from(agent, team, List.of(), List.of());
    }

    public static AgentResponse from(Agent agent) {
        return from(agent, null, List.of(), List.of());
    }
}
