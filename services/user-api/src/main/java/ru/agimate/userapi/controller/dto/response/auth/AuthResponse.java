package ru.agimate.userapi.controller.dto.response.auth;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * @param refreshToken null for web callers on purpose: their token belongs in the httpOnly cookie,
 *                     and putting a copy in a body script can read would undo the point of it
 */
@Schema(description = "Authentication Response DTO")
public record AuthResponse(
        @Schema(description = "JWT access token", example = "eyJhbGciOiJFUzI1NiJ9...")
        String accessToken,

        @Schema(description = "Identifier of the refresh token issued alongside",
                example = "550e8400-e29b-41d4-a716-446655440000")
        String refreshTokenId,

        @Schema(description = "Refresh token itself — native clients only; web clients receive it "
                + "as an httpOnly cookie instead")
        String refreshToken,

        @Schema(description = "Lifetime of the access token in seconds", example = "3600")
        int expiresIn,

        @Schema(description = "Session this pair belongs to — the row that names this device in the "
                + "device list", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID sessionId
) {}
