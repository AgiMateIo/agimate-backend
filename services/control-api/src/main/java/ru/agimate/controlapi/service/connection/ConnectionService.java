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
import ru.agimate.controlapi.connectors.core.ConnectorEnvFactory;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.FullCodes;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.connectors.core.events.ConnectorCreatedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorDeletedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorModifiedEvent;
import ru.agimate.controlapi.connectors.core.dto.IntegrationValidationResult;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.OAuthCredentials;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.enums.ConnectionAuthStatus;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SecretRepository;
import ru.agimate.controlapi.service.secret.SecretService;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lifecycle of connector instances (telegram/mcp) on top of the single {@code connections} registry.
 * Creation, the secret and the test are valid for integration connectors (a type with
 * {@code credentialFields}); the listing returns every connection of the user, with filters on real
 * fields. Outbound credentials live in {@code secrets} (envelope) and are addressed by
 * {@code connection.secretId}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConnectionService {

    private static final String SECRET_ENTITY = "connection";
    /** Secret for validating incoming webhooks (a single value, AAD owner = connection.id). */
    public static final String WEBHOOK_SECRET_ENTITY = "connection_webhook";

    @Value("${app.integration.webhook-base-url}")
    private String webhookBaseUrl;

    private final ConnectionRepository connectionRepository;
    private final SecretRepository secretRepository;
    private final ConnectorRepository connectorRepository;
    private final ConnectorRegistry connectorRegistry;
    private final ConnectorEnvFactory envFactory;
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
        // Exclusive for connectors where a second instance of the same account is harmful (a telegram
        // bot has one webhook), null where several accounts on one endpoint are the point (MCP).
        String exclusiveSubCode = handler.allowsMultipleInstances() ? null : subCode;
        if (exclusiveSubCode != null
                && connectionRepository.existsByConnectorCodeAndUserIdAndExclusiveSubCodeAndDeletedAtIsNull(
                        connectorCode, userId, exclusiveSubCode)) {
            throw new ConflictStatusException("Connection already exists for " + connectorCode + ": " + subCode);
        }

        // The id is needed before the secrets are encrypted (the AAD binding) and before the webhook URL — so the row is saved first.
        Connection connection = connectionRepository.save(Connection.builder()
                .id(UUIDUtils.generateUUIDv8())
                .connectorCode(connectorCode)
                .subCode(subCode)
                .exclusiveSubCode(exclusiveSubCode)
                .fullCode(resolveFullCode(handler, connectorCode, userId, subCode, name))
                .userId(userId)
                .name(name)
                .authStatus(validationResult.authorizationRequired()
                        ? ConnectionAuthStatus.PENDING_AUTH
                        : ConnectionAuthStatus.AUTHORIZED)
                .build());

        Map<String, String> stored = new LinkedHashMap<>(credentials);
        stored.putAll(validationResult.derivedCredentials());
        Secret secret = secretService.store(SECRET_ENTITY, connection.getId(), stored);
        connection.setSecretId(secret.getId());
        if (handler.supportsWebhooks()) {
            Secret webhookSecret = secretService.storeValue(
                    WEBHOOK_SECRET_ENTITY, connection.getId(), CryptoUtils.randomHex(32));
            connection.setWebhookSecretId(webhookSecret.getId());
        }
        connection = connectionRepository.save(connection);

        if (handler.supportsWebhooks()) {
            String webhookUrl = webhookBaseUrl + "/webhook/" + connection.getId();
            handler.setupWebhook(envFactory.withCredentials(connection, stored, null), webhookUrl);
        }

        log.info("Created connection {} for user {}: {} ({}, {})",
                connection.getId(), userId, connectorCode, subCode, connection.getAuthStatus());

        // For a connection that still has to be authorised the event waits for the callback: tool
        // discovery hangs on it and would get a 401 for certain — there is no token yet.
        if (connection.isUsable()) {
            eventPublisher.publishEvent(new ConnectorCreatedEvent(
                    connectorCode, connection.getId().toString(), connection.getUserId()));
        }

        return connection;
    }

    /**
     * The client-facing handle. For a multi-instance connector the identifier no longer tells
     * instances apart — two accounts share a URL — so the discriminator is the name the user gave,
     * with a numeric suffix on a collision. Computed once, here: renaming a connection later must not
     * silently rename the agent's tools.
     */
    private String resolveFullCode(IntegrationConnectorHandler handler, String connectorCode,
                                   UUID userId, String subCode, String name) {
        if (!handler.allowsMultipleInstances()) {
            return FullCodes.fullCode(connectorCode, subCode);
        }
        String base = FullCodes.nameSlug(connectorCode, subCode, name);
        String candidate = FullCodes.withSlug(connectorCode, base);
        int suffix = 2;
        while (connectionRepository.existsByFullCodeAndUserIdAndDeletedAtIsNull(candidate, userId)) {
            candidate = FullCodes.withSlug(connectorCode, base + "_" + suffix++);
        }
        return candidate;
    }

    /** A user's connections with filters on real fields (every parameter is optional). */
    public List<Connection> list(UUID userId, String connectorCode, Boolean enabled) {
        String code = (connectorCode == null || connectorCode.isBlank()) ? null : connectorCode;
        return connectionRepository.findByUserIdFiltered(userId, code, enabled);
    }

    public Connection getOwnedConnection(UUID connectionId, UUID userId) {
        return connectionRepository.findByIdNotDeleted(connectionId)
                .filter(c -> c.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundStatusException("Connection not found"));
    }

    /**
     * A check of an existing instance: decrypting the credentials plus {@code validateCredentials} (the
     * platform's reachability and auth). Side-effect free — suitable for every integration type.
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
            handler.removeWebhook(envFactory.withCredentials(connection, revealCredentials(connection), null));
        } catch (Exception e) {
            log.warn("Failed to remove webhook for connection {}: {}", id, e.getMessage());
        }

        connectionRepository.softDelete(connection.getId(), LocalDateTime.now());
        log.info("Deleted connection {}", id);

        eventPublisher.publishEvent(new ConnectorDeletedEvent(
                connection.getConnectorCode(), connection.getId().toString()));
    }

    /**
     * Replacing the credentials of an OAuth connection is refused rather than tolerated: there is no
     * password to change there, and the URL cannot be changed this way anyway (the {@code sub_code}
     * invariant below is the server's URL). The real payoff is elsewhere — with this path closed the
     * secret has exactly one writer, the refresh job, and needs no locking at all.
     */
    @Transactional
    public Connection updateSecret(UUID id, UUID userId, Map<String, String> credentials) {
        Connection connection = getOwnedConnection(id, userId);
        if (usesOAuth(connection)) {
            throw new BadRequestStatusException(
                    "This connection is authorized through the provider; re-connect it instead of "
                            + "editing credentials");
        }
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
            handler.setupWebhook(envFactory.withCredentials(connection, credentials, null), webhookUrl);
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
            // Switching an unauthorized connection back on does not make it usable, and the listeners
            // on this event would immediately walk into a 401 on tool discovery.
            if (Boolean.TRUE.equals(enabled) && saved.isUsable()) {
                eventPublisher.publishEvent(new ConnectorCreatedEvent(
                        saved.getConnectorCode(), saved.getId().toString(), saved.getUserId()));
            } else if (!Boolean.TRUE.equals(enabled)) {
                eventPublisher.publishEvent(new ConnectorDeletedEvent(
                        saved.getConnectorCode(), saved.getId().toString()));
            }
        }

        return saved;
    }

    /** Whether the connection holds an OAuth grant rather than credentials the user typed. */
    private boolean usesOAuth(Connection connection) {
        return OAuthCredentials.isOAuth(revealCredentials(connection));
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
