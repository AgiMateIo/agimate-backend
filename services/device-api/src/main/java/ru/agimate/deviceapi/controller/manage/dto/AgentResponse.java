package ru.agimate.deviceapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.AgentTool;
import ru.agimate.deviceapi.database.entities.AgentTrigger;
import ru.agimate.deviceapi.database.entities.AgenticTeam;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "Agent response")
public record AgentResponse(
        @Schema(description = "Agent ID")
        UUID id,

        @Schema(description = "API key public ID")
        UUID apiKeyPubId,

        @Schema(description = "Agent name")
        String name,

        @Schema(description = "Agent prompt")
        String prompt,

        @Schema(description = "Allow all triggers")
        boolean triggersAllowAll,

        @Schema(description = "Triggers destination")
        String triggersTo,

        @Schema(description = "Authorized tool names")
        List<String> tools,

        @Schema(description = "Subscribed trigger names")
        List<String> triggers,

        @Schema(description = "Webhook URL")
        String webhookUrl,

        @Schema(description = "Whether webhook auth header is configured")
        boolean hasWebhookAuth,

        @Schema(description = "Agentic team ID")
        UUID agenticTeamId,

        @Schema(description = "Agentic team name")
        String agenticTeamName,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the agent was created")
        LocalDateTime createdAt
) {
    public static AgentResponse from(Agent agent, List<AgentTool> tools, List<AgentTrigger> triggers, AgenticTeam team) {
        return new AgentResponse(
                agent.getPubId(),
                agent.getApiKeyPubId(),
                agent.getName(),
                agent.getPrompt(),
                agent.isTriggersAllowAll(),
                agent.getTriggersTo(),
                tools.stream().map(AgentTool::getToolName).toList(),
                triggers.stream().map(AgentTrigger::getTriggerName).toList(),
                agent.getWebhookUrl(),
                agent.hasWebhookAuth(),
                team != null ? team.getPubId() : null,
                team != null ? team.getName() : null,
                agent.getCreatedAt()
        );
    }

    public static AgentResponse from(Agent agent, List<AgentTool> tools, List<AgentTrigger> triggers) {
        return from(agent, tools, triggers, null);
    }
}
