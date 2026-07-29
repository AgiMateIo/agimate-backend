package ru.agimate.userapi.security.jwt;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.agimate.common.security.jwt.JwtProperties;
import ru.agimate.userapi.util.CookieUtils;

import java.time.Duration;
import java.util.Arrays;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final JwtProperties jwtProperties;

    private static final String REFRESH_TOKEN_COOKIE_NAME = "refreshToken";

    /**
     * Refresh token ids ({@code jti}) already spent by a refresh or a logout — replaying one must not
     * mint a new session. Keyed by id and never by the token string: the two are not interchangeable,
     * and mixing them is what silently disabled this check before.
     *
     * <p>Entries expire on the refresh lifetime, past which the token is dead anyway; there is
     * deliberately no size cap, since evicting an entry would resurrect a revoked token. State is per
     * instance and lost on restart — flagged in the review as the reason this belongs in the database
     * once there is more than one replica.
     */
    private Cache<String, Boolean> usedRefreshTokenIds;

    @PostConstruct
    void initUsedTokenCache() {
        usedRefreshTokenIds = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(jwtProperties.getRefreshExpiration()))
                .build();
    }

    public boolean isUsed(String refreshTokenId) {
        return refreshTokenId != null && usedRefreshTokenIds.getIfPresent(refreshTokenId) != null;
    }

    public void markUsed(String refreshTokenId) {
        if (refreshTokenId != null) {
            usedRefreshTokenIds.put(refreshTokenId, Boolean.TRUE);
        }
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
