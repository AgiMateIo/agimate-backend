package ru.agimate.userapi.controller.dto.request.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Refresh Token Request DTO
 */
@Schema(description = "Refresh Token Request DTO")
public record RefreshRequest(
        @Schema(description = "Refresh token identifier", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
        String refreshTokenId
) {}
