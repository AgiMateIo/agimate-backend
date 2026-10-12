package ru.agimate.controlapi.service.mcp.oauth;

/** What the token endpoint answers: a token pair, or an OAuth error the client is meant to read. */
public sealed interface TokenResult {

    /** @param expiresIn lifetime of the access token, seconds */
    record Issued(String accessToken, String refreshToken, long expiresIn) implements TokenResult {}

    /** @param error an RFC 6749 error code ({@code invalid_grant}, {@code invalid_request}, …) */
    record Refused(String error, String description) implements TokenResult {}

    static TokenResult refused(String error, String description) {
        return new Refused(error, description);
    }
}
