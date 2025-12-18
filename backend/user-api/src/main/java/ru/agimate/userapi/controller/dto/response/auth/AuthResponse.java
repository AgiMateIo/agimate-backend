package ru.agimate.userapi.controller.dto.response.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Authentication Response DTO
 */
@Schema(description = "Authentication Response DTO")
public record AuthResponse(
        @Schema(description = "JWT access token", example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
        String accessToken,

        @Schema(description = "Refresh token identifier", example = "550e8400-e29b-41d4-a716-446655440000")
        String refreshTokenId
) {}