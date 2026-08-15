package ru.agimate.userapi.service.auth;

import java.util.UUID;

/**
 * What a sign-in or a refresh produces. The refresh token is always here; whether it ends up in a
 * cookie or in the response body is the caller's decision, and the only difference between the two
 * clients at this level.
 *
 * @param refreshTokenId the {@code jti} inside {@code refreshToken}, which the web client sends
 *                       back alongside its cookie
 */
public record IssuedTokens(
        UUID sessionId,
        String accessToken,
        int accessExpiresIn,
        String refreshToken,
        String refreshTokenId
) {}
