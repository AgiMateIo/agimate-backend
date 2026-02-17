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

        @Schema(description = "Device auth key ID")
        UUID deviceAuthKeyId,

        @Schema(description = "Trigger ID")
        String triggerId,

        @Schema(description = "Trigger type")
        String triggerType,

        @Schema(description = "Trigger name")
        String triggerName,

        @Schema(description = "Trigger source")
        String triggerSource,

        @Schema(description = "Request device ID")
        String requestDeviceId,

        @Schema(description = "Linked device ID")
        String linkedDeviceId,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the trigger occurred")
        LocalDateTime occurredAt,

        @Schema(description = "Trigger data")
        Map<String, Object> triggerData,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When the log was created")
        LocalDateTime createdAt
) {
    public static TriggerLogResponse from(TriggerLog triggerLog) {
        return new TriggerLogResponse(
                triggerLog.getPubId(),
                triggerLog.getDeviceAuthKey().getPubId(),
                triggerLog.getTriggerId(),
                triggerLog.getTriggerType(),
                triggerLog.getTriggerName(),
                triggerLog.getTriggerSource(),
                triggerLog.getRequestDeviceId(),
                triggerLog.getLinkedDeviceId(),
                triggerLog.getOccurredAt(),
                triggerLog.getTriggerData(),
                triggerLog.getCreatedAt()
        );
    }
}
