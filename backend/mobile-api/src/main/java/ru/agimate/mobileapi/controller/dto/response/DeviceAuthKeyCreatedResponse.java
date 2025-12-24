package ru.agimate.mobileapi.controller.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.mobileapi.database.entities.DeviceAuthKey;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Response when a new device auth key is created (includes the actual key)")
public record DeviceAuthKeyCreatedResponse(
        @Schema(description = "Public ID of the key")
        UUID id,

        @Schema(description = "Key name/label")
        String name,

        @Schema(description = "The actual API key - SAVE THIS NOW, it will not be shown again!")
        String key,

        @Schema(description = "Key description")
        String description,

        @Schema(description = "Creation timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt
) {
    public static DeviceAuthKeyCreatedResponse from(DeviceAuthKey deviceAuthKey, String plaintextKey) {
        return new DeviceAuthKeyCreatedResponse(
                deviceAuthKey.getPubId(),
                deviceAuthKey.getName(),
                plaintextKey,
                deviceAuthKey.getDescription(),
                deviceAuthKey.getCreatedAt()
        );
    }
}
