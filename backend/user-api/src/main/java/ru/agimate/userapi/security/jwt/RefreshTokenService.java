package ru.agimate.userapi.security.jwt;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.agimate.common.security.jwt.JwtProperties;
import ru.agimate.userapi.util.CookieUtils;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final JwtProperties jwtProperties;

    @Value("${app.oauth.cookie-secure:false}")
    private boolean cookieSecure;

    private static final String REFRESH_TOKEN_COOKIE_NAME = "refreshToken";

    // Blacklist to track used refresh tokens to prevent replay attacks
    private final Map<String, Instant> usedRefreshTokens = new ConcurrentHashMap<>();


    public boolean isAlreadyUsed(String jti) {
        return usedRefreshTokens.containsKey(jti);
    }

    public void markTokenAsUsed(String jti) {
        usedRefreshTokens.put(jti, Instant.now());
    }


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

    public void setHttpOnlyRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        // Set the new refresh token as an httpOnly cookie
        CookieUtils.setHttpOnlyCookie(
                response,
                REFRESH_TOKEN_COOKIE_NAME,
                refreshToken,
                "/",
                jwtProperties.getRefreshExpiration(),
                cookieSecure
        );
    }

    public void deleteRefreshTokenCookie(HttpServletResponse response) {
        CookieUtils.deleteCookie(response, REFRESH_TOKEN_COOKIE_NAME);
    }
}