package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.database.entities.ToolCallLog;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Tool use log entry")
public record ToolCallLogResponse(
        @Schema(description = "Tool use log ID")
        UUID id,

        @Schema(description = "Agent public ID")
        UUID agentId,

        @Schema(description = "Connector code")
        String connectorCode,

        @Schema(description = "Connector instance id (connections.id)")
        String connectionId,

        @Schema(description = "Agent session identifier")
        String agentSessionId,

        @Schema(description = "Tool use correlation ID")
        String externalId,

        @Schema(description = "Tool name")
        String name,

        @Schema(description = "Tool input")
        Map<String, Object> input,

        @Schema(description = "Access effect (ALLOW/DENY)")
        AccessEffect accessEffect,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the output was received")
        LocalDateTime finishAt,

        @Schema(description = "Tool output")
        String output,

        @Schema(description = "Tool error")
        String error,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the log was created")
        LocalDateTime createdAt
) {
    public static ToolCallLogResponse from(ToolCallLog toolCallLog) {
        return new ToolCallLogResponse(
                toolCallLog.getId(),
                toolCallLog.getAgentId(),
                toolCallLog.getConnectorCode(),
                toolCallLog.getConnectionId(),
                toolCallLog.getAgentSessionId(),
                toolCallLog.getExternalId(),
                toolCallLog.getName(),
                toolCallLog.getInput(),
                toolCallLog.getAccessEffect(),
                toolCallLog.getFinishAt(),
                toolCallLog.getOutput(),
                toolCallLog.getError(),
                toolCallLog.getCreatedAt()
        );
    }
}
