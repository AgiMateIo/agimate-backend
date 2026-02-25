package ru.agimate.deviceapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.ToolUseLog;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Tool use log entry")
public record ToolUseLogResponse(
        @Schema(description = "Tool use log ID")
        UUID id,

        @Schema(description = "API key public ID (agent identifier)")
        UUID apiKeyPubId,

        @Schema(description = "Connector public ID")
        String connectorPubId,

        @Schema(description = "Tool use correlation ID")
        String toolUseId,

        @Schema(description = "Tool name")
        String toolName,

        @Schema(description = "Tool parameters")
        Map<String, Object> toolParams,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the result was received")
        LocalDateTime resultAt,

        @Schema(description = "Tool result")
        String result,

        @Schema(description = "Tool error")
        String error,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the log was created")
        LocalDateTime createdAt
) {
    public static ToolUseLogResponse from(ToolUseLog toolUseLog) {
        return new ToolUseLogResponse(
                toolUseLog.getPubId(),
                toolUseLog.getApiKeyPubId(),
                toolUseLog.getConnectorPubId(),
                toolUseLog.getToolUseId(),
                toolUseLog.getToolName(),
                toolUseLog.getToolParams(),
                toolUseLog.getResultAt(),
                toolUseLog.getResult(),
                toolUseLog.getError(),
                toolUseLog.getCreatedAt()
        );
    }
}
