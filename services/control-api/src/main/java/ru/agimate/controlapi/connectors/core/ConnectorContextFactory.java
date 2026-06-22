package ru.agimate.controlapi.connectors.core;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.integrations.IntegrationEncryptionService;
import ru.agimate.controlapi.database.entities.IntegrationCredentials;

import java.util.Map;
import java.util.UUID;

/**
 * Единственное место сборки {@link ConnectorContext} из сущностей платформы.
 */
@Component
@RequiredArgsConstructor
public class ConnectorContextFactory {

    private final IntegrationEncryptionService encryptionService;

    /** Полный контекст integration-коннектора: с расшифровкой credentials (тулы, таски). */
    public ConnectorContext forIntegration(IntegrationCredentials credentials, UUID agentId, String agentSessionId) {
        return build(credentials,
                encryptionService.decryptCredentials(credentials.getEncryptedData()), agentId, agentSessionId);
    }

    /** Контекст с уже известной мапой credentials — когда расшифровка не нужна (lifecycle-вызовы). */
    public ConnectorContext withCredentials(IntegrationCredentials credentials,
                                            Map<String, String> decrypted,
                                            UUID agentId) {
        return build(credentials, decrypted, agentId, null);
    }

    /** Webhook hot path: валидация/нормализация не требует расшифрованных credentials. */
    public ConnectorContext forWebhook(IntegrationCredentials credentials) {
        return build(credentials, Map.of(), null, null);
    }

    public ConnectorContext internal(String identity, UUID userId, UUID agentId, String agentSessionId) {
        return new ConnectorContext(identity, userId, agentId, agentSessionId, Map.of(), null);
    }

    private ConnectorContext build(IntegrationCredentials credentials, Map<String, String> decrypted,
                                   UUID agentId, String agentSessionId) {
        return new ConnectorContext(
                credentials.getId().toString(),
                credentials.getUserId(),
                agentId,
                agentSessionId,
                decrypted,
                credentials.getWebhookSecret());
    }
}
