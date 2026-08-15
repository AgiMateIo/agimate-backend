package ru.agimate.userapi.security.jwt;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.agimate.common.security.jwt.JwtProperties;
import ru.agimate.userapi.util.CookieUtils;

import java.util.Arrays;

/**
 * The cookie half of the browser flow, and nothing else. Whether a refresh token is still good is
 * decided by the session registry
 * ({@link ru.agimate.userapi.service.auth.AuthSessionService}) — it used to be decided by a
 * per-instance list of spent ids here, which a restart erased and a second replica never saw.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final JwtProperties jwtProperties;

    private static final String REFRESH_TOKEN_COOKIE_NAME = "refreshToken";

    public String getRefreshTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }

        return Arrays.stream(request.getCookies())
                .filter(cookie -> REFRESH_TOKEN_COOKIE_NAME.equals(cookie.getName()))
                .findFirst()
                .map(jakarta.servlet.http.Cookie::getValue)
                .orElse(null);
    }

    public void setHttpOnlyRefreshTokenCookie(HttpServletResponse response, String refreshToken,
                                              String cookieDomain, boolean cookieSecure) {
        CookieUtils.setHttpOnlyCookie(
                response,
                REFRESH_TOKEN_COOKIE_NAME,
                refreshToken,
                "/",
                jwtProperties.getRefreshExpiration(),
                cookieSecure,
                cookieDomain
        );
    }

    public void deleteRefreshTokenCookie(HttpServletResponse response, String cookieDomain) {
        CookieUtils.deleteCookie(response, REFRESH_TOKEN_COOKIE_NAME, cookieDomain);
    }
}
