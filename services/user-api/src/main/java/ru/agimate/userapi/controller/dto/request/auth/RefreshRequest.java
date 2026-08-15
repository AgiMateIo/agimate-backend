package ru.agimate.userapi.controller.dto.request.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Two clients, one endpoint. A browser sends the id and keeps the token in its cookie; an
 * application has no cookie to keep it in and sends the token itself. Exactly one of the two ways
 * applies to any given caller.
 */
@Schema(description = "Refresh Token Request DTO")
public record RefreshRequest(
        @Schema(description = "Refresh token identifier. Web clients only: proves the caller is the "
                + "page that started the login, not merely a request carrying its cookie",
                example = "550e8400-e29b-41d4-a716-446655440000")
        String refreshTokenId,

        @Schema(description = "Refresh token itself. Native clients only — a browser must leave it "
                + "in the httpOnly cookie, where script cannot reach it",
                example = "eyJhbGciOiJFUzI1NiJ9...")
        String refreshToken
) {}
