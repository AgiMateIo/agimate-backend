package ru.agimate.deviceapi.connectors.integrations;

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
import ru.agimate.deviceapi.connectors.integrations.events.IntegrationCreatedEvent;
import ru.agimate.deviceapi.connectors.integrations.events.IntegrationDeletedEvent;
import ru.agimate.deviceapi.database.entities.IntegrationCredentials;
import ru.agimate.deviceapi.database.enums.ConnectorType;
import ru.agimate.deviceapi.database.repositories.ConnectorRepository;
import ru.agimate.deviceapi.database.repositories.IntegrationCredentialsRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IntegrationService {

    @Value("${app.integration.webhook-base-url}")
    private String webhookBaseUrl;

    private final IntegrationCredentialsRepository integrationCredentialsRepository;
    private final ConnectorRepository connectorRepository;
    private final IntegrationsRegistry integrationsRegistry;
    private final IntegrationEncryptionService encryptionService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public IntegrationCredentials createIntegration(
            UUID userId,
            String connectorCode,
            Map<String, String> credentials,
            String name
    ) {
        if (!connectorRepository.existsByCodeAndType(connectorCode, ConnectorType.INTEGRATION)) {
            throw new BadRequestStatusException("Integration connector not found: " + connectorCode);
        }

        var handler = integrationsRegistry.getHandler(connectorCode);

        var validationResult = handler.validateCredentials(credentials);
        if (!validationResult.valid()) {
            throw new ValidationErrorStatusException(validationResult.errorField(), validationResult.errorMessage());
        }

        if (integrationCredentialsRepository.existsByConnectorCodeAndUserIdAndPlatformIdentifierAndDeletedAtIsNull(
                connectorCode, userId, validationResult.identifier())) {
            throw new ConflictStatusException("Integration already exists for " + connectorCode + ": " + validationResult.identifier());
        }

        // Encrypt credentials
        String encryptedData = encryptionService.encryptCredentials(credentials);

        // Generate webhook secret only if platform supports webhooks
        String webhookSecret = handler.supportsWebhooks()
                ? encryptionService.generateSecureToken()
                : null;

        IntegrationCredentials integrationCredentials = IntegrationCredentials.builder()
                .connectorCode(connectorCode)
                .userId(userId)
                .name(name)
                .platformIdentifier(validationResult.identifier())
                .encryptedData(encryptedData)
                .webhookSecret(webhookSecret)
                .build();

        // Setup webhook before save — on failure the transaction rolls back automatically
        if (handler.supportsWebhooks()) {
            String webhookUrl = webhookBaseUrl + "/webhook/integration/" + integrationCredentials.getId();
            handler.setupWebhook(integrationCredentials, credentials, webhookUrl);
        }

        integrationCredentials = integrationCredentialsRepository.save(integrationCredentials);

        log.info("Created integration {} for user {}: {} ({})",
                integrationCredentials.getId(), userId, connectorCode, validationResult.identifier());

        eventPublisher.publishEvent(new IntegrationCreatedEvent(
                integrationCredentials.getId(), connectorCode));

        return integrationCredentials;
    }

    public List<IntegrationCredentials> getIntegrations(UUID userId) {
        return integrationCredentialsRepository.findByUserIdNotDeleted(userId);
    }

    public List<IntegrationCredentials> getIntegrations(UUID userId, String connectorCode) {
        if (connectorCode == null || connectorCode.isBlank()) {
            return getIntegrations(userId);
        }
        return integrationCredentialsRepository.findByUserIdAndConnectorCodeNotDeleted(userId, connectorCode);
    }

    public IntegrationCredentials getIntegrationCredentials(UUID integrationCredentialsId, UUID userId) {
        return integrationCredentialsRepository.findByIdNotDeleted(integrationCredentialsId)
                .filter(i -> i.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundStatusException("Integration not found"));
    }

    @Transactional
    public void deleteIntegration(UUID id, UUID userId) {
        IntegrationCredentials integrationCredentials = getIntegrationCredentials(id, userId);

        // Remove webhook if platform supports it
        try {
            var handler = integrationsRegistry.getHandler(integrationCredentials.getConnectorCode());
            Map<String, String> credentials = encryptionService.decryptCredentials(integrationCredentials.getEncryptedData());
            handler.removeWebhook(credentials);
        } catch (Exception e) {
            log.warn("Failed to remove webhook for integration {}: {}", id, e.getMessage());
        }

        integrationCredentialsRepository.softDelete(integrationCredentials.getId(), LocalDateTime.now());
        log.info("Deleted integration {}", id);

        eventPublisher.publishEvent(new IntegrationDeletedEvent(
                integrationCredentials.getId(), integrationCredentials.getConnectorCode()));
    }

    @Transactional
    public IntegrationCredentials updateCredentials(UUID id, UUID userId, Map<String, String> credentials) {
        IntegrationCredentials integrationCredentials = getIntegrationCredentials(id, userId);
        var handler = integrationsRegistry.getHandler(integrationCredentials.getConnectorCode());

        var validationResult = handler.validateCredentials(credentials);
        if (!validationResult.valid()) {
            throw new ValidationErrorStatusException(validationResult.errorField(), validationResult.errorMessage());
        }

        // Ensure same platform identifier (same bot/account)
        if (!integrationCredentials.getPlatformIdentifier().equals(validationResult.identifier())) {
            throw new BadRequestStatusException(
                    "Credentials belong to a different account: " + validationResult.identifier()
                            + " (expected: " + integrationCredentials.getPlatformIdentifier() + ")");
        }

        // Re-encrypt
        integrationCredentials.setEncryptedData(encryptionService.encryptCredentials(credentials));

        // Re-setup webhook if platform supports it
        if (handler.supportsWebhooks()) {
            String webhookUrl = webhookBaseUrl + "/webhook/integration/" + integrationCredentials.getId();
            handler.setupWebhook(integrationCredentials, credentials, webhookUrl);
        }

        return integrationCredentialsRepository.save(integrationCredentials);
    }

    @Transactional
    public IntegrationCredentials patchIntegration(UUID id, UUID userId, Boolean enabled, String name) {
        IntegrationCredentials integrationCredentials = getIntegrationCredentials(id, userId);

        boolean enabledChanged = false;
        Boolean previousEnabled = integrationCredentials.getEnabled();
        if (enabled != null && !enabled.equals(previousEnabled)) {
            integrationCredentials.setEnabled(enabled);
            enabledChanged = true;
        }
        if (name != null) {
            integrationCredentials.setName(name);
        }

        IntegrationCredentials saved = integrationCredentialsRepository.save(integrationCredentials);

        if (enabledChanged) {
            if (Boolean.TRUE.equals(enabled)) {
                eventPublisher.publishEvent(new IntegrationCreatedEvent(
                        saved.getId(), saved.getConnectorCode()));
            } else {
                eventPublisher.publishEvent(new IntegrationDeletedEvent(
                        saved.getId(), saved.getConnectorCode()));
            }
        }

        return saved;
    }
}
