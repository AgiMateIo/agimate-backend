package ru.agimate.deviceapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.service.AppService;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Schema(description = "App information (without the actual key)")
public record AppResponse(
        @Schema(description = "Public ID of the app")
        UUID pubId,

        @Schema(description = "App name/label", example = "My Home Device")
        String name,

        @Schema(description = "App description")
        String description,

        @Schema(description = "Masked key ID for identification", example = "dvckZ3h5****")
        String maskedKeyId,

        @Schema(description = "Whether the app is enabled")
        boolean enabled,

        @Schema(description = "Creation timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime createdAt,

        @Schema(description = "Last update timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime updatedAt,

        @Schema(description = "Device features")
        Map<String, Object> features
) {
    public static AppResponse from(App app) {
        String maskedKeyId = AppService.APP_KEY_PREFIX + app.getKeyId().substring(0, 4) + "****";
        return new AppResponse(
                app.getPubId(),
                app.getName(),
                app.getDescription(),
                maskedKeyId,
                app.getEnabled(),
                app.getCreatedAt(),
                app.getUpdatedAt(),
                app.getDeviceFeatures()
        );
    }
}
