package ru.agimate.controlapi.connectors.core;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.service.secret.SecretService;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.repositories.SecretRepository;

import java.util.Map;
import java.util.UUID;

/**
 * The only place a {@link ConnectorEnv} is assembled from platform entities. Outbound credentials are
 * decrypted from {@code secrets} by {@code connection.secretId} (with the AAD bound to
 * {@code connection.id}).
 */
@Component
@RequiredArgsConstructor
public class ConnectorEnvFactory {

    private final SecretRepository secretRepository;
    private final SecretService secretService;

    /** The full env of a connector instance: with credentials decrypted (tools, jobs). */
    public ConnectorEnv forConnection(Connection connection, UUID agentId, UUID runId, UUID channelId) {
        // Tools do not need the webhook secret — we do not decrypt it on every dispatch.
        return build(connection, decryptCredentials(connection), agentId, runId, channelId, null);
    }

    /** Env with an already-known credentials map — lifecycle calls (setup/remove webhook). */
    public ConnectorEnv withCredentials(Connection connection, Map<String, String> decrypted, UUID agentId) {
        return build(connection, decrypted, agentId, null, null, revealWebhookSecret(connection));
    }

    /** Webhook hot path: validation and normalisation need no decrypted credentials. */
    public ConnectorEnv forWebhook(Connection connection) {
        return build(connection, Map.of(), null, null, null, revealWebhookSecret(connection));
    }

    public ConnectorEnv internal(String connectionId, UUID userId, UUID agentId, UUID runId,
                                 UUID channelId, UUID sessionId) {
        return new ConnectorEnv(connectionId, userId, agentId, runId, channelId, sessionId, Map.of(), null);
    }

    /**
     * Env for listing an instance's tools or blocks: addressing by {@code connectionId} only (dynamic
     * connectors read a per-instance cache), with no caller and no credentials. A static connector
     * needs no dependencies (secrets) at all.
     */
    public static ConnectorEnv listing(UUID connectionId) {
        return new ConnectorEnv(connectionId == null ? null : connectionId.toString(),
                null, null, null, null, null, Map.of(), null);
    }

    private Map<String, String> decryptCredentials(Connection connection) {
        if (connection.getSecretId() == null) {
            return Map.of();
        }
        Secret secret = secretRepository.findById(connection.getSecretId())
                .orElseThrow(() -> new ConnectorException(
                        "Secret not found for connection " + connection.getId()));
        return secretService.reveal(secret, connection.getId());
    }

    private String revealWebhookSecret(Connection connection) {
        if (connection.getWebhookSecretId() == null) {
            return null;
        }
        Secret secret = secretRepository.findById(connection.getWebhookSecretId())
                .orElseThrow(() -> new ConnectorException(
                        "Webhook secret not found for connection " + connection.getId()));
        return secretService.revealValue(secret, connection.getId());
    }

    private ConnectorEnv build(Connection connection, Map<String, String> decrypted,
                                   UUID agentId, UUID runId, UUID channelId, String webhookSecret) {
        return new ConnectorEnv(
                connection.getId().toString(),
                connection.getUserId(),
                agentId,
                runId,
                channelId,
                null,
                decrypted,
                webhookSecret);
    }
}
