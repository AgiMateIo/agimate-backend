package ru.agimate.controlapi.connectors.core;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.secret.SecretService;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.repositories.SecretRepository;

import java.util.Map;
import java.util.UUID;

/**
 * Единственное место сборки {@link ConnectorContext} из сущностей платформы.
 * Outbound-credentials расшифровываются из {@code secrets} по {@code connection.secretId}
 * (AAD-привязка к {@code connection.id}).
 */
@Component
@RequiredArgsConstructor
public class ConnectorContextFactory {

    private final SecretRepository secretRepository;
    private final SecretService secretService;

    /** Полный контекст коннектора-экземпляра: с расшифровкой credentials (тулы, таски). */
    public ConnectorContext forConnection(Connection connection, UUID agentId, UUID channelId) {
        return build(connection, decryptCredentials(connection), agentId, channelId);
    }

    /** Контекст с уже известной мапой credentials — когда расшифровка не нужна (lifecycle-вызовы). */
    public ConnectorContext withCredentials(Connection connection, Map<String, String> decrypted, UUID agentId) {
        return build(connection, decrypted, agentId, null);
    }

    /** Webhook hot path: валидация/нормализация не требует расшифрованных credentials. */
    public ConnectorContext forWebhook(Connection connection) {
        return build(connection, Map.of(), null, null);
    }

    public ConnectorContext internal(String identity, UUID userId, UUID agentId, UUID channelId) {
        return new ConnectorContext(identity, userId, agentId, channelId, Map.of(), null);
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

    private ConnectorContext build(Connection connection, Map<String, String> decrypted,
                                   UUID agentId, UUID channelId) {
        return new ConnectorContext(
                connection.getId().toString(),
                connection.getUserId(),
                agentId,
                channelId,
                decrypted,
                connection.getWebhookSecret());
    }
}
