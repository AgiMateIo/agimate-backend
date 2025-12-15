package ru.agimate.userapi.security.jwt;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.agimate.userapi.security.UserPrincipal;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final JwtUtils jwtUtils;

    @Value("${jwt.refresh.expiration.ms:604800000}") // 7 days default
    private Long refreshTokenExpirationMs;

    @Value("${jwt.refresh.reuse.window.ms:30000}") // 30 seconds default reuse window
    private Long refreshTokenReuseWindowMs;

    // Blacklist to track used refresh tokens to prevent replay attacks
    private final Map<String, Instant> usedRefreshTokens = new ConcurrentHashMap<>();

    public String createRefreshToken(UserPrincipal userPrincipal) {
        return jwtUtils.generateRefreshToken(userPrincipal);
    }

    public RefreshToken verifyRefreshToken(String token) {
        // Check if token is in blacklist (already used)
        String jti = jwtUtils.extractJti(token);
        if (jti != null && usedRefreshTokens.containsKey(jti)) {
            Instant blacklistedTime = usedRefreshTokens.get(jti);
            // Remove token from blacklist if it's older than the reuse window
            // This allows reuse for a short period to handle race conditions, but prevents long-term reuse
            if (blacklistedTime.isBefore(Instant.now().minusMillis(refreshTokenReuseWindowMs))) {
                usedRefreshTokens.remove(jti);
            } else {
                return null; // Token has been used recently, reject it
            }
        }

        // Validate JWT-based refresh token
        if (jwtUtils.validateRefreshToken(token)) {
            // Extract user ID from the token
            Long userId = jwtUtils.extractUserId(token);
            if (userId == null) {
                return null; // Invalid token if user ID is missing
            }

            return new RefreshToken(
                    token,
                    userId,
                    Instant.ofEpochMilli(jwtUtils.extractExpiration(token).getTime())
            );
        }

        return null;
    }

    public void markTokenAsUsed(String token) {
        String jti = jwtUtils.extractJti(token);
        if (jti != null) {
            usedRefreshTokens.put(jti, Instant.now());
        }
    }

    public static class RefreshToken {
        private final String token;
        private final Long userId;
        private final Instant expiryDate;

        public RefreshToken(String token, Long userId, Instant expiryDate) {
            this.token = token;
            this.userId = userId;
            this.expiryDate = expiryDate;
        }

        public String getToken() {
            return token;
        }

        public Long getUserId() {
            return userId;
        }

        public Instant getExpiryDate() {
            return expiryDate;
        }
    }
}