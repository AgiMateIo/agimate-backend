package ru.agimate.mobileapi.service;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensolutionlab.httpclients.clients.CentrifugoClient;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.ServiceUnavailableStatusException;
import ru.agimate.mobileapi.config.CentrifugoProperties;

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
     * Publishes a message to a Centrifugo channel.
     *
     * @param channel The channel name
     * @param data    The message data (will be serialized to JSON)
     * @throws ServiceUnavailableStatusException if Centrifugo is unavailable or publishing fails
     */
    public void publishMessage(String channel, Object data) {
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
     * Creates a test message with current timestamp.
     *
     * @return A map containing type and timestamp
     */
    public Map<String, Object> createTestMessage() {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "test");
        message.put("timestamp", System.currentTimeMillis());
        return message;
    }

    /**
     * Generates a Centrifugo connection token (JWT) for WebSocket connection.
     * This token does not contain channel claim and is used for initial connection.
     *
     * @param subject           The subject (user/device ID)
     * @param expirationSeconds Token expiration time in seconds
     * @return JWT connection token
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
     * Generates a Centrifugo subscription token (JWT) for the specified channel.
     *
     * @param subject           The subject (user/device ID)
     * @param channel           The channel name
     * @param expirationSeconds Token expiration time in seconds
     * @return JWT subscription token
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
