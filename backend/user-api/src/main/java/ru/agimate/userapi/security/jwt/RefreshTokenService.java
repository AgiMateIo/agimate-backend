package ru.agimate.userapi.security.jwt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.agimate.userapi.security.UserPrincipal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RefreshTokenService {

    @Value("${jwt.refresh.expiration.ms:604800000}") // 7 days default
    private Long refreshTokenExpirationMs;

    // In-memory storage for refresh tokens (in production, use database)
    private final Map<String, RefreshToken> refreshTokenStore = new ConcurrentHashMap<>();

    public String createRefreshToken(UserPrincipal userPrincipal) {
        String token = UUID.randomUUID().toString();
        RefreshToken refreshToken = new RefreshToken(
                token,
                userPrincipal.getId(),
                Instant.now().plusMillis(refreshTokenExpirationMs)
        );
        
        refreshTokenStore.put(token, refreshToken);
        return token;
    }

    public RefreshToken verifyRefreshToken(String token) {
        RefreshToken refreshToken = refreshTokenStore.get(token);
        
        if (refreshToken != null && refreshToken.getExpiryDate().isAfter(Instant.now())) {
            return refreshToken;
        }
        
        // Remove expired token
        if (refreshToken != null) {
            refreshTokenStore.remove(token);
        }
        
        return null;
    }

    public void deleteRefreshToken(String token) {
        refreshTokenStore.remove(token);
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