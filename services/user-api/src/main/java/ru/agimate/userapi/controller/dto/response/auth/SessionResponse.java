package ru.agimate.userapi.controller.dto.response.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.userapi.database.entities.AuthClient;
import ru.agimate.userapi.database.entities.AuthSession;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Active session (one signed-in device)")
public record SessionResponse(
        @Schema(description = "Session id — the value to pass when dropping this device")
        UUID id,

        @Schema(description = "What kind of client signed in", example = "NATIVE")
        AuthClient client,

        @Schema(description = "Device name or browser, as reported when signing in", example = "Pixel 8")
        String deviceLabel,

        @Schema(description = "When this device signed in")
        LocalDateTime createdAt,

        @Schema(description = "When this device last refreshed its tokens")
        LocalDateTime lastSeenAt
) {

    public static SessionResponse of(AuthSession session) {
        return new SessionResponse(
                session.getId(),
                session.getClient(),
                session.getDeviceLabel(),
                session.getCreatedAt(),
                session.getLastSeenAt());
    }
}
