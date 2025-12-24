package ru.agimate.mobileapi.controller.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.mobileapi.database.entities.DeviceAuthKey;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Device auth key information (without the actual key)")
public record DeviceAuthKeyResponse(
        @Schema(description = "Public ID of the key")
        UUID id,

        @Schema(description = "Key name/label", example = "My Home Device")
        String name,

        @Schema(description = "Key description")
        String description,

        @Schema(description = "Masked key ID for identification", example = "amobZ3h5****")
        String maskedKeyId,

        @Schema(description = "Whether the key is enabled")
        boolean enabled,

        @Schema(description = "Creation timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt
) {
    public static DeviceAuthKeyResponse from(DeviceAuthKey key) {
        String maskedKeyId = "amob" + key.getKeyId().substring(0, 4) + "****";
        return new DeviceAuthKeyResponse(
                key.getPubId(),
                key.getName(),
                key.getDescription(),
                maskedKeyId,
                key.getEnabled(),
                key.getCreatedAt()
        );
    }
}
