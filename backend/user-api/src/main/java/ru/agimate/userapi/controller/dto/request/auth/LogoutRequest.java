package ru.agimate.userapi.controller.dto.request.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Logout Request DTO
 */
@Schema(description = "Logout Request DTO")
public record LogoutRequest(
        @Schema(description = "Refresh token identifier to invalidate", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
        String refreshTokenId
) {}
