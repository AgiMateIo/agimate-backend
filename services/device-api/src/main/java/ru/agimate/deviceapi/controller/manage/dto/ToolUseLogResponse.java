package ru.agimate.deviceapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.abac.AccessEffect;
import ru.agimate.deviceapi.database.entities.ToolUseLog;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Tool use log entry")
public record ToolUseLogResponse(
        @Schema(description = "Tool use log ID")
        UUID id,

        @Schema(description = "Agent public ID")
        UUID agentPubId,

        @Schema(description = "Connector code")
        String connectorCode,

        @Schema(description = "Connector identity")
        String identity,

        @Schema(description = "Agent session identifier")
        String agentSessionId,

        @Schema(description = "Tool use correlation ID")
        String toolUseId,

        @Schema(description = "Tool name")
        String toolName,

        @Schema(description = "Tool input")
        Map<String, Object> input,

        @Schema(description = "Access effect (ALLOW/DENY)")
        AccessEffect accessEffect,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the output was received")
        LocalDateTime outputAt,

        @Schema(description = "Tool output")
        String output,

        @Schema(description = "Tool error")
        String error,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the log was created")
        LocalDateTime createdAt
) {
    public static ToolUseLogResponse from(ToolUseLog toolUseLog) {
        return new ToolUseLogResponse(
                toolUseLog.getPubId(),
                toolUseLog.getAgentPubId(),
                toolUseLog.getConnectorCode(),
                toolUseLog.getIdentity(),
                toolUseLog.getAgentSessionId(),
                toolUseLog.getToolUseId(),
                toolUseLog.getToolName(),
                toolUseLog.getInput(),
                toolUseLog.getAccessEffect(),
                toolUseLog.getOutputAt(),
                toolUseLog.getOutput(),
                toolUseLog.getError(),
                toolUseLog.getCreatedAt()
        );
    }
}
