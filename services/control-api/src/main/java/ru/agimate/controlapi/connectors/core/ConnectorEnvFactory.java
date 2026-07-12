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
 * Единственное место сборки {@link ConnectorEnv} из сущностей платформы.
 * Outbound-credentials расшифровываются из {@code secrets} по {@code connection.secretId}
 * (AAD-привязка к {@code connection.id}).
 */
@Component
@RequiredArgsConstructor
public class ConnectorEnvFactory {

    private final SecretRepository secretRepository;
    private final SecretService secretService;

    /** Полная env коннектора-экземпляра: с расшифровкой credentials (тулы, таски). */
    public ConnectorEnv forConnection(Connection connection, UUID agentId, UUID channelId) {
        return build(connection, decryptCredentials(connection), agentId, channelId);
    }

    /** Env с уже известной мапой credentials — когда расшифровка не нужна (lifecycle-вызовы). */
    public ConnectorEnv withCredentials(Connection connection, Map<String, String> decrypted, UUID agentId) {
        return build(connection, decrypted, agentId, null);
    }

    /** Webhook hot path: валидация/нормализация не требует расшифрованных credentials. */
    public ConnectorEnv forWebhook(Connection connection) {
        return build(connection, Map.of(), null, null);
    }

    public ConnectorEnv internal(String connectionId, UUID userId, UUID agentId, UUID channelId, UUID sessionId) {
        return new ConnectorEnv(connectionId, userId, agentId, channelId, sessionId, Map.of(), null);
    }

    /**
     * Env для листинга тулов/блоков экземпляра: только адресация {@code connectionId}
     * (динамические коннекторы читают per-instance кэш), без вызывающего и без credentials.
     * Статический — зависимостей (secrets) не требует.
     */
    public static ConnectorEnv listing(UUID connectionId) {
        return new ConnectorEnv(connectionId == null ? null : connectionId.toString(),
                null, null, null, null, Map.of(), null);
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

    private ConnectorEnv build(Connection connection, Map<String, String> decrypted,
                                   UUID agentId, UUID channelId) {
        return new ConnectorEnv(
                connection.getId().toString(),
                connection.getUserId(),
                agentId,
                channelId,
                null,
                decrypted,
                connection.getWebhookSecret());
    }
}
