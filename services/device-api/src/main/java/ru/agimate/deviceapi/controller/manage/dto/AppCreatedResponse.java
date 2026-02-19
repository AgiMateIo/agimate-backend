package ru.agimate.deviceapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.App;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Response when a new app is created (includes the actual key)")
public record AppCreatedResponse(
        @Schema(description = "Public ID of the app")
        UUID id,

        @Schema(description = "App name/label")
        String name,

        @Schema(description = "The actual API key - SAVE THIS NOW, it will not be shown again!")
        String key,

        @Schema(description = "App description")
        String description,

        @Schema(description = "Creation timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt
) {
    public static AppCreatedResponse from(App app, String plaintextKey) {
        return new AppCreatedResponse(
                app.getPubId(),
                app.getName(),
                plaintextKey,
                app.getDescription(),
                app.getCreatedAt()
        );
    }
}
