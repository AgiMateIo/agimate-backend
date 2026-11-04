package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.App;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Response when a new app is created (includes the actual key)")
public record AppCreatedResponse(
        @Schema(description = "Public ID of the app")
        UUID id,

        @Schema(description = "App kind; empty until the app links and names itself", example = "desktop")
        String code,

        @Schema(description = "Human-readable app title the client declared", example = "AgiMate Desktop")
        String title,

        @Schema(description = "Client version", example = "1.4.2")
        String version,

        @Schema(description = "The actual key - SAVE THIS NOW, it will not be shown again!")
        String fullKey,

        @Schema(description = "User's note about the key")
        String description,

        @Schema(description = "Creation timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime createdAt,

        @Schema(description = "Last update timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime updatedAt
) {
    public static AppCreatedResponse from(App app, String plaintextKey) {
        return new AppCreatedResponse(
                app.getId(),
                app.getCode(),
                app.getTitle(),
                app.getVersion(),
                plaintextKey,
                app.getDescription(),
                app.getCreatedAt(),
                app.getUpdatedAt()
        );
    }
}
