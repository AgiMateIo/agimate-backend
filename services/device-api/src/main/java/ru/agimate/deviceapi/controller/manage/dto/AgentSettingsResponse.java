package ru.agimate.deviceapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.AgentSettings;
import ru.agimate.deviceapi.database.entities.AgentTool;
import ru.agimate.deviceapi.database.entities.AgentTrigger;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "Agent settings response")
public record AgentSettingsResponse(
        @Schema(description = "Agent settings ID")
        UUID id,

        @Schema(description = "API key public ID")
        UUID apiKeyPubId,

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

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the settings were created")
        LocalDateTime createdAt
) {
    public static AgentSettingsResponse from(AgentSettings settings, List<AgentTool> tools, List<AgentTrigger> triggers) {
        return new AgentSettingsResponse(
                settings.getPubId(),
                settings.getApiKeyPubId(),
                settings.getPrompt(),
                settings.isTriggersAllowAll(),
                settings.getTriggersTo(),
                tools.stream().map(AgentTool::getToolName).toList(),
                triggers.stream().map(AgentTrigger::getTriggerName).toList(),
                settings.getCreatedAt()
        );
    }
}
