package ru.agimate.controlapi.connectors.integrations;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.ValidationErrorStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorContext;
import ru.agimate.controlapi.connectors.core.ConnectorContextFactory;
import ru.agimate.controlapi.connectors.core.ConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.FullCodes;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.events.ConnectorCreatedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorDeletedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorModifiedEvent;
import ru.agimate.controlapi.service.secret.SecretService;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.enums.ConnectorType;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SecretRepository;
import ru.agimate.common.util.CryptoUtils;
import ru.agimate.common.util.UUIDUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Жизненный цикл integration-экземпляров (telegram/mcp) поверх единого реестра {@code connections}.
 * Outbound-credentials хранятся в {@code secrets} (envelope) и адресуются {@code connection.secretId};
 * {@code sub_code} = канонический identifier платформы, {@code full_code} = стабильный клиентский
 * handle ({@code mcp_context7}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IntegrationService {

    private static final String SECRET_ENTITY = "connection";

    @Value("${app.integration.webhook-base-url}")
    private String webhookBaseUrl;

    private final ConnectionRepository connectionRepository;
    private final SecretRepository secretRepository;
    private final ConnectorRepository connectorRepository;
    private final ConnectorRegistry connectorRegistry;
    private final ConnectorContextFactory contextFactory;
    private final SecretService secretService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Connection createIntegration(UUID userId, String connectorCode,
                                        Map<String, String> credentials, String name) {
        if (!connectorRepository.existsByCodeAndType(connectorCode, ConnectorType.INTEGRATION)) {
            throw new BadRequestStatusException("Integration connector not found: " + connectorCode);
        }

        var handler = integrationHandler(connectorCode);

        var validationResult = handler.validateCredentials(credentials);
        if (!validationResult.valid()) {
            throw new ValidationErrorStatusException(validationResult.errorField(), validationResult.errorMessage());
        }

        String subCode = validationResult.identifier();
        if (connectionRepository.existsByConnectorCodeAndUserIdAndSubCodeAndDeletedAtIsNull(
                connectorCode, userId, subCode)) {
            throw new ConflictStatusException("Integration already exists for " + connectorCode + ": " + subCode);
        }

        String webhookSecret = handler.supportsWebhooks() ? CryptoUtils.randomHex(32) : null;

        // id нужен до шифрования секрета (AAD-привязка) и до webhook URL — сохраняем строку первой.
        Connection connection = connectionRepository.save(Connection.builder()
                .id(UUIDUtils.generateUUIDv8())
                .connectorCode(connectorCode)
                .subCode(subCode)
                .fullCode(FullCodes.fullCode(connectorCode, subCode))
                .userId(userId)
                .name(name)
                .webhookSecret(webhookSecret)
                .build());

        Secret secret = secretService.store(SECRET_ENTITY, connection.getId(), credentials);
        connection.setSecretId(secret.getId());
        connection = connectionRepository.save(connection);

        if (handler.supportsWebhooks()) {
            String webhookUrl = webhookBaseUrl + "/webhook/integration/" + connection.getId();
            handler.setupWebhook(contextFactory.withCredentials(connection, credentials, null), webhookUrl);
        }

        log.info("Created connection {} for user {}: {} ({})",
                connection.getId(), userId, connectorCode, subCode);

        eventPublisher.publishEvent(new ConnectorCreatedEvent(
                connectorCode, connection.getId().toString(), connection.getUserId()));

        return connection;
    }

    public List<Connection> getIntegrations(UUID userId) {
        return connectionRepository.findByUserIdNotDeleted(userId);
    }

    public List<Connection> getIntegrations(UUID userId, String connectorCode) {
        if (connectorCode == null || connectorCode.isBlank()) {
            return getIntegrations(userId);
        }
        return connectionRepository.findByUserIdAndConnectorCodeNotDeleted(userId, connectorCode);
    }

    public Connection getIntegrationCredentials(UUID connectionId, UUID userId) {
        return connectionRepository.findByIdNotDeleted(connectionId)
                .filter(c -> c.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundStatusException("Integration not found"));
    }

    /**
     * Проверка существующей интеграции: расшифровка credentials + {@code validateCredentials}
     * (доступность/auth платформы). Без сайд-эффектов — пригодно для всех типов интеграций.
     */
    public IntegrationValidationResult validateExisting(UUID id, UUID userId) {
        Connection connection = getIntegrationCredentials(id, userId);
        IntegrationConnectorHandler handler = integrationHandler(connection.getConnectorCode());
        return handler.validateCredentials(revealCredentials(connection));
    }

    /**
     * Тулы конкретного экземпляра через SPI {@code getTools(ctx)}: для динамических коннекторов
     * (MCP) — из кэша по identity, для статических — их {@code @Tool}-набор.
     */
    public List<ConnectorToolSpec> getInstanceTools(UUID id, UUID userId) {
        Connection connection = getIntegrationCredentials(id, userId);
        ConnectorHandler handler = connectorRegistry.findHandler(connection.getConnectorCode())
                .orElseThrow(() -> new BadRequestStatusException(
                        "Unsupported platform: " + connection.getConnectorCode()));
        ConnectorContext context = contextFactory.internal(id.toString(), userId, null, null);
        return List.copyOf(handler.getTools(context).values());
    }

    @Transactional
    public void deleteIntegration(UUID id, UUID userId) {
        Connection connection = getIntegrationCredentials(id, userId);

        try {
            var handler = integrationHandler(connection.getConnectorCode());
            handler.removeWebhook(contextFactory.withCredentials(connection, revealCredentials(connection), null));
        } catch (Exception e) {
            log.warn("Failed to remove webhook for connection {}: {}", id, e.getMessage());
        }

        connectionRepository.softDelete(connection.getId(), LocalDateTime.now());
        log.info("Deleted connection {}", id);

        eventPublisher.publishEvent(new ConnectorDeletedEvent(
                connection.getConnectorCode(), connection.getId().toString()));
    }

    @Transactional
    public Connection updateCredentials(UUID id, UUID userId, Map<String, String> credentials) {
        Connection connection = getIntegrationCredentials(id, userId);
        var handler = integrationHandler(connection.getConnectorCode());

        var validationResult = handler.validateCredentials(credentials);
        if (!validationResult.valid()) {
            throw new ValidationErrorStatusException(validationResult.errorField(), validationResult.errorMessage());
        }

        // Same platform instance (same bot/account/server).
        if (!connection.getSubCode().equals(validationResult.identifier())) {
            throw new BadRequestStatusException(
                    "Credentials belong to a different account: " + validationResult.identifier()
                            + " (expected: " + connection.getSubCode() + ")");
        }

        Secret secret = secretRepository.findById(connection.getSecretId())
                .orElseThrow(() -> new NotFoundStatusException("Secret not found for connection " + id));
        secretService.update(secret, connection.getId(), credentials);

        if (handler.supportsWebhooks()) {
            String webhookUrl = webhookBaseUrl + "/webhook/integration/" + connection.getId();
            handler.setupWebhook(contextFactory.withCredentials(connection, credentials, null), webhookUrl);
        }

        eventPublisher.publishEvent(new ConnectorModifiedEvent(
                connection.getConnectorCode(), connection.getId().toString(), connection.getUserId()));

        return connection;
    }

    @Transactional
    public Connection patchIntegration(UUID id, UUID userId, Boolean enabled, String name) {
        Connection connection = getIntegrationCredentials(id, userId);

        boolean enabledChanged = false;
        if (enabled != null && !enabled.equals(connection.getEnabled())) {
            connection.setEnabled(enabled);
            enabledChanged = true;
        }
        if (name != null) {
            connection.setName(name);
        }

        Connection saved = connectionRepository.save(connection);

        if (enabledChanged) {
            if (Boolean.TRUE.equals(enabled)) {
                eventPublisher.publishEvent(new ConnectorCreatedEvent(
                        saved.getConnectorCode(), saved.getId().toString(), saved.getUserId()));
            } else {
                eventPublisher.publishEvent(new ConnectorDeletedEvent(
                        saved.getConnectorCode(), saved.getId().toString()));
            }
        }

        return saved;
    }

    private Map<String, String> revealCredentials(Connection connection) {
        if (connection.getSecretId() == null) {
            return Map.of();
        }
        Secret secret = secretRepository.findById(connection.getSecretId())
                .orElseThrow(() -> new NotFoundStatusException("Secret not found for connection " + connection.getId()));
        return secretService.reveal(secret, connection.getId());
    }

    private IntegrationConnectorHandler integrationHandler(String connectorCode) {
        return connectorRegistry.findIntegrationHandler(connectorCode)
                .orElseThrow(() -> new BadRequestStatusException("Unsupported platform: " + connectorCode));
    }
}
