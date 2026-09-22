package ru.agimate.controlapi.realtime;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.CentrifugoProperties;
import ru.agimate.controlapi.controller.app.dto.CentrifugoTokenResponse;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;

/**
 * Client tokens for Centrifugo. A subscription token is the access grant to a channel, so the channel
 * name comes from {@link RealtimeChannels} and ownership is checked by the caller.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CentrifugoTokens {

    private final CentrifugoProperties centrifugoProperties;

    /**
     * Without the signing key every issued client token is rejected by Centrifugo — a failure that
     * otherwise surfaces as a silent WebSocket disconnect in the browser, far from its cause.
     */
    @PostConstruct
    void warnOnIncompleteConfiguration() {
        if (!centrifugoProperties.isEnabled()) {
            return;
        }
        if (centrifugoProperties.getPrivateKey() == null || centrifugoProperties.getPrivateKey().isBlank()) {
            log.warn("centrifugo.privateKey is not set — client tokens cannot be signed. "
                    + "Run ops/dev-init.sh to generate the local configuration");
        }
    }

    /**
     * Issues the full token bundle a client needs to subscribe to {@code channel}: a connection
     * token, a subscription token scoped to {@code channel}, the channel name and the public WS URL.
     * TTL and WS URL come from {@link CentrifugoProperties} — the single place they are resolved.
     *
     * <p><b>Authorization is the caller's responsibility.</b> A subscription token <i>is</i> the
     * access grant to a channel, so callers must first verify the principal may subscribe to
     * {@code channel} (e.g. own the session behind {@code webchat:{sessionId}}). This method only signs.
     */
    public CentrifugoTokenResponse issueTokens(String subject, String channel) {
        long ttl = centrifugoProperties.getTokenTtlSeconds();
        String connectionToken = generateConnectionToken(subject, ttl);
        String subscriptionToken = generateSubscriptionToken(subject, channel, ttl);
        String wsUrl = centrifugoProperties.getPublicUrl() + "/connection/websocket";
        return new CentrifugoTokenResponse(connectionToken, subscriptionToken, channel, wsUrl);
    }

    /**
     * Carries no channel claim: it authenticates the WebSocket connection and grants nothing by
     * itself. Signed with the Centrifugo ES256 pair, which is separate from the user JWT one.
     */
    public String generateConnectionToken(String subject, long expirationSeconds) {
        PrivateKey privateKey = getPrivateKey();

        return Jwts.builder()
                .subject(subject)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationSeconds * 1000))
                .signWith(privateKey, Jwts.SIG.ES256)
                .compact();
    }

    /**
     * Unlike the connection token this one <i>is</i> the access grant to {@code channel} — see the
     * authorization note on {@link #issueTokens} before calling it directly.
     */
    public String generateSubscriptionToken(String subject, String channel, long expirationSeconds) {
        PrivateKey privateKey = getPrivateKey();

        return Jwts.builder()
                .subject(subject)
                .claim("channel", channel)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationSeconds * 1000))
                .signWith(privateKey, Jwts.SIG.ES256)
                .compact();
    }

    private PrivateKey getPrivateKey() {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(centrifugoProperties.getPrivateKey());
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            return keyFactory.generatePrivate(keySpec);
        } catch (Exception e) {
            throw new JwtException("Failed to load Centrifugo private key", e);
        }
    }
}
