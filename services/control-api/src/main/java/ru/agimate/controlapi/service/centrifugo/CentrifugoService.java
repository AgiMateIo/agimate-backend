package ru.agimate.controlapi.service.centrifugo;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensolutionlab.httpclients.clients.CentrifugoClient;
import org.opensolutionlab.httpclients.models.requests.publication.PublishRequest;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.ServiceUnavailableStatusException;
import ru.agimate.controlapi.config.CentrifugoProperties;
import ru.agimate.controlapi.controller.app.dto.CentrifugoTokenResponse;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CentrifugoService {

    private final CentrifugoClient centrifugoClient;
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
        if (centrifugoProperties.getApiKey() == null || centrifugoProperties.getApiKey().isBlank()) {
            log.warn("centrifugo.api-key is not set — server-side publishing will be rejected. "
                    + "It must match http_api.key in ops/centrifugo/config.yaml");
        }
    }

    /** Wraps {@code data} into a {@code CentrifugoMessage} envelope; see {@link #publish} for the raw form. */
    public void publishMessage(String channel, String type, Object data) {
        publish(channel, new CentrifugoMessage(type, data));
    }

    /**
     * Publishes raw data to a Centrifugo channel without wrapping it into CentrifugoMessage.
     * Used when the caller already owns the wire envelope (e.g. {@link ru.agimate.controlapi.service.dto.AgentMessage}).
     */
    public void publish(String channel, Object data) {
        if (!centrifugoProperties.isEnabled()) {
            log.warn("Centrifugo is disabled, skipping publish to channel: {}", channel);
            return;
        }

        try {
            log.debug("Publishing message to Centrifugo channel: {}", channel);

            centrifugoClient.publish(channel, data);

            log.info("Successfully published message to channel: {}", channel);
        } catch (Exception e) {
            log.error("Failed to publish message to Centrifugo channel '{}': {}",
                    channel, e.getMessage(), e);
            throw new ServiceUnavailableStatusException(
                    "Failed to publish message to real-time service: " + e.getMessage(), e);
        }
    }

    /**
     * Same, plus tags: Centrifugo filters deliveries by them on its side, so a subscriber receives
     * only the matching subset instead of the whole channel.
     */
    public void publishMessage(String channel, String type, Object data, Map<String, String> tags) {
        var centrifugoMessage = new CentrifugoMessage(type, data);

        if (!centrifugoProperties.isEnabled()) {
            log.warn("Centrifugo is disabled, skipping publish to channel: {}", channel);
            return;
        }

        try {
            log.debug("Publishing message to Centrifugo channel: {}, tags: {}", channel, tags);

            PublishRequest<CentrifugoMessage> request = PublishRequest.<CentrifugoMessage>builder()
                    .channel(channel)
                    .data(centrifugoMessage)
                    .tags(tags)
                    .build();

            centrifugoClient.publish(request);

            log.info("Successfully published message to channel: {}", channel);
        } catch (Exception e) {
            log.error("Failed to publish message to Centrifugo channel '{}': {}",
                    channel, e.getMessage(), e);
            throw new ServiceUnavailableStatusException(
                    "Failed to publish message to real-time service: " + e.getMessage(), e);
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
