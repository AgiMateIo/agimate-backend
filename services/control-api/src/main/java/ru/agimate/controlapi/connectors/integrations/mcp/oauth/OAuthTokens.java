package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * A token endpoint's answer, normalised.
 *
 * @param refreshToken {@code null} is normal, not an anomaly: the spec forbids assuming one will be
 *                     issued, and then the connection lives until the access token dies
 * @param expiresAt    {@code null} when the server sent no {@code expires_in} — «unknown», never
 *                     «expired»: a made-up default would make the refresh job fire on a schedule that
 *                     follows from nothing
 * @param scope        what was actually granted, which may differ from what was asked
 */
public record OAuthTokens(
        String accessToken,
        String refreshToken,
        LocalDateTime expiresAt,
        String scope
) {

    public static OAuthTokens from(JsonNode body, LocalDateTime now) {
        String accessToken = body.path("access_token").asText("");
        String refreshToken = body.path("refresh_token").asText("");
        String scope = body.path("scope").asText("");
        JsonNode expiresIn = body.get("expires_in");
        LocalDateTime expiresAt = expiresIn != null && expiresIn.canConvertToLong() && expiresIn.asLong() > 0
                ? now.plusSeconds(expiresIn.asLong())
                : null;
        return new OAuthTokens(
                accessToken.isBlank() ? null : accessToken,
                refreshToken.isBlank() ? null : refreshToken,
                expiresAt,
                scope.isBlank() ? null : scope);
    }
}
