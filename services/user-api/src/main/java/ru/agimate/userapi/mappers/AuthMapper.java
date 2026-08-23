package ru.agimate.userapi.mappers;

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NonNull;
import ru.agimate.userapi.controller.dto.response.auth.AuthResponse;
import ru.agimate.userapi.service.auth.IssuedTokens;

/**
 * The two shapes a freshly opened session comes back in. Kept in one place because both the OAuth
 * flow and the password flow answer with them, and a refresh token that leaks into a web response
 * body would be a silent regression in whichever of the two drifted.
 */
@UtilityClass
public class AuthMapper {

    /** The refresh token is omitted: for a browser it belongs in the httpOnly cookie, and only there. */
    public static @NonNull AuthResponse forWeb(IssuedTokens tokens) {
        return new AuthResponse(tokens.accessToken(), tokens.refreshTokenId(), null,
                tokens.accessExpiresIn(), tokens.sessionId());
    }

    /** An installed application has no cookie jar the login could have written to, so it is told. */
    public static @NonNull AuthResponse forNative(IssuedTokens tokens) {
        return new AuthResponse(tokens.accessToken(), tokens.refreshTokenId(), tokens.refreshToken(),
                tokens.accessExpiresIn(), tokens.sessionId());
    }
}
