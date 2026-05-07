package ru.agimate.deviceapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.AgenticTeam;
import ru.agimate.deviceapi.database.entities.AgentType;
import ru.agimate.deviceapi.service.AgentService;

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

        @Schema(description = "Agent prompt")
        String prompt,

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

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the agent was created")
        LocalDateTime createdAt
) {
    public static AgentResponse from(Agent agent, AgenticTeam team, List<AgentSkillSummary> skills) {
        String maskedKeyId = AgentService.AGENT_KEY_PREFIX + agent.getKeyId().substring(0, 4) + "****";
        return new AgentResponse(
                agent.getPubId(),
                agent.getName(),
                agent.getDescription(),
                maskedKeyId,
                agent.getPrompt(),
                agent.getType(),
                agent.getWebhookUrl(),
                agent.hasWebhookAuth(),
                agent.isEnabled(),
                team != null ? team.getPubId() : null,
                team != null ? team.getName() : null,
                skills != null ? skills : List.of(),
                agent.getCreatedAt()
        );
    }

    public static AgentResponse from(Agent agent, AgenticTeam team) {
        return from(agent, team, List.of());
    }

    public static AgentResponse from(Agent agent) {
        return from(agent, null, List.of());
    }
}
