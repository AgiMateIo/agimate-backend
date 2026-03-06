package ru.agimate.deviceapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.TriggerLog;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Trigger log entry")
public record TriggerLogResponse(
        @Schema(description = "Trigger log ID")
        UUID id,

        @Schema(description = "Connector code")
        String connectorCode,

        @Schema(description = "Identity")
        String identity,

        @Schema(description = "Trigger ID")
        String triggerId,

        @Schema(description = "Trigger name")
        String triggerName,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the trigger occurred")
        LocalDateTime occurredAt,

        @Schema(description = "Trigger input")
        Map<String, Object> triggerInput,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the log was created")
        LocalDateTime createdAt
) {
    public static TriggerLogResponse from(TriggerLog triggerLog) {
        return new TriggerLogResponse(
                triggerLog.getPubId(),
                triggerLog.getConnectorCode(),
                triggerLog.getIdentity(),
                triggerLog.getTriggerId(),
                triggerLog.getTriggerName(),
                triggerLog.getOccurredAt(),
                triggerLog.getTriggerInput(),
                triggerLog.getCreatedAt()
        );
    }
}
