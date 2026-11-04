package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.service.AppService;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Schema(description = "App information (without the actual key)")
public record AppResponse(
        @Schema(description = "Public ID of the app")
        UUID id,

        @Schema(description = "App kind the client declared at link time (desktop, chromium, …); "
                + "empty until the first link carrying the app block", example = "desktop")
        String code,

        @Schema(description = "Human-readable app title the client declared", example = "AgiMate Desktop")
        String title,

        @Schema(description = "Client version", example = "1.4.2")
        String version,

        @Schema(description = "User's note about the key")
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

        @Schema(description = "App features")
        Map<String, Object> features
) {
    public static AppResponse from(App app) {
        String maskedKeyId = AppService.APP_KEY_PREFIX + app.getKeyId().substring(0, 4) + "****";
        return new AppResponse(
                app.getId(),
                app.getCode(),
                app.getTitle(),
                app.getVersion(),
                app.getDescription(),
                maskedKeyId,
                app.getEnabled(),
                app.getCreatedAt(),
                app.getUpdatedAt(),
                app.getInfo()
        );
    }
}
