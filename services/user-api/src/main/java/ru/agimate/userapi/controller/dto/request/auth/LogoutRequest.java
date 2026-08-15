package ru.agimate.userapi.controller.dto.request.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/** Same two ways of naming a session as {@link RefreshRequest}. */
@Schema(description = "Logout Request DTO")
public record LogoutRequest(
        @Schema(description = "Refresh token identifier to invalidate. Web clients only",
                example = "550e8400-e29b-41d4-a716-446655440000")
        String refreshTokenId,

        @Schema(description = "Refresh token to invalidate. Native clients only",
                example = "eyJhbGciOiJFUzI1NiJ9...")
        String refreshToken
) {}
