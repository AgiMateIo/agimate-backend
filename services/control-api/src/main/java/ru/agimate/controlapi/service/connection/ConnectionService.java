package ru.agimate.controlapi.service.connection;

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
import ru.agimate.common.util.CryptoUtils;
import ru.agimate.common.util.UUIDUtils;
import ru.agimate.controlapi.connectors.core.ConnectorContextFactory;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.FullCodes;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.connectors.core.events.ConnectorCreatedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorDeletedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorModifiedEvent;
import ru.agimate.controlapi.connectors.integrations.IntegrationValidationResult;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.enums.IdentityScope;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SecretRepository;
import ru.agimate.controlapi.service.secret.SecretService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Жизненный цикл connector-экземпляров (telegram/mcp) поверх единого реестра {@code connections}.
 * Создание/секрет/тест валидны для integration-коннекторов (тип с {@code credentialFields}); listing
 * отдаёт все connection пользователя с фильтрами по реальным полям. Outbound-credentials — в
 * {@code secrets} (envelope), адресуются {@code connection.secretId}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConnectionService {

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
    public Connection create(UUID userId, String connectorCode,
                             Map<String, String> credentials, String name) {
        boolean isIntegration = connectorRepository.findById(connectorCode)
                .map(Connector::isIntegration).orElse(false);
        if (!isIntegration) {
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
            throw new ConflictStatusException("Connection already exists for " + connectorCode + ": " + subCode);
        }

        String webhookSecret = handler.supportsWebhooks() ? CryptoUtils.randomHex(32) : null;

        // id нужен до шифрования секрета (AAD-привязка) и до webhook URL — сохраняем строку первой.
        Connection connection = connectionRepository.save(Connection.builder()
                .id(UUIDUtils.generateUUIDv8())
                .identityScope(IdentityScope.INSTANCE)
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
            String webhookUrl = webhookBaseUrl + "/webhook/" + connection.getId();
            handler.setupWebhook(contextFactory.withCredentials(connection, credentials, null), webhookUrl);
        }

        log.info("Created connection {} for user {}: {} ({})",
                connection.getId(), userId, connectorCode, subCode);

        eventPublisher.publishEvent(new ConnectorCreatedEvent(
                connectorCode, connection.getId().toString(), connection.getUserId()));

        return connection;
    }

    /** Connection пользователя с фильтрами по реальным полям (все параметры опциональны). */
    public List<Connection> list(UUID userId, String connectorCode, IdentityScope scope, Boolean enabled) {
        String code = (connectorCode == null || connectorCode.isBlank()) ? null : connectorCode;
        return connectionRepository.findByUserIdFiltered(userId, code, scope, enabled);
    }

    public Connection getOwnedConnection(UUID connectionId, UUID userId) {
        return connectionRepository.findByIdNotDeleted(connectionId)
                .filter(c -> c.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundStatusException("Connection not found"));
    }

    /**
     * Проверка существующего экземпляра: расшифровка credentials + {@code validateCredentials}
     * (доступность/auth платформы). Без сайд-эффектов — пригодно для всех integration-типов.
     */
    public IntegrationValidationResult validate(UUID id, UUID userId) {
        Connection connection = getOwnedConnection(id, userId);
        IntegrationConnectorHandler handler = integrationHandler(connection.getConnectorCode());
        return handler.validateCredentials(revealCredentials(connection));
    }

    @Transactional
    public void delete(UUID id, UUID userId) {
        Connection connection = getOwnedConnection(id, userId);

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
    public Connection updateSecret(UUID id, UUID userId, Map<String, String> credentials) {
        Connection connection = getOwnedConnection(id, userId);
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
            String webhookUrl = webhookBaseUrl + "/webhook/" + connection.getId();
            handler.setupWebhook(contextFactory.withCredentials(connection, credentials, null), webhookUrl);
        }

        eventPublisher.publishEvent(new ConnectorModifiedEvent(
                connection.getConnectorCode(), connection.getId().toString(), connection.getUserId()));

        return connection;
    }

    @Transactional
    public Connection update(UUID id, UUID userId, Boolean enabled, String name) {
        Connection connection = getOwnedConnection(id, userId);

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
