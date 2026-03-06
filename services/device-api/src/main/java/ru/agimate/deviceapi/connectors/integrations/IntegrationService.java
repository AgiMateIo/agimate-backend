package ru.agimate.deviceapi.connectors.integrations;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.ValidationErrorStatusException;
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
    private final IntegrationPlatformRegistry platformRegistry;
    private final IntegrationEncryptionService encryptionService;

    @Transactional
    public IntegrationCredentials createIntegration(
            UUID userPubId,
            String connectorCode,
            Map<String, String> credentials,
            String name
    ) {
        if (!connectorRepository.existsByCodeAndType(connectorCode, ConnectorType.INTEGRATION)) {
            throw new BadRequestStatusException("Integration connector not found: " + connectorCode);
        }

        var handler = platformRegistry.getHandler(connectorCode);

        var validationResult = handler.validateCredentials(credentials);
        if (!validationResult.valid()) {
            throw new ValidationErrorStatusException(validationResult.errorField(), validationResult.errorMessage());
        }

        if (integrationCredentialsRepository.existsByConnectorCodeAndUserPubIdAndPlatformIdentifierAndDeletedAtIsNull(
                connectorCode, userPubId, validationResult.identifier())) {
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
                .userPubId(userPubId)
                .name(name)
                .platformIdentifier(validationResult.identifier())
                .encryptedData(encryptedData)
                .webhookSecret(webhookSecret)
                .build();

        // Setup webhook before save — on failure the transaction rolls back automatically
        if (handler.supportsWebhooks()) {
            String webhookUrl = webhookBaseUrl + "/webhook/integration/" + integrationCredentials.getPubId();
            handler.setupWebhook(integrationCredentials, credentials, webhookUrl);
        }

        integrationCredentials = integrationCredentialsRepository.save(integrationCredentials);

        log.info("Created integration {} for user {}: {} ({})",
                integrationCredentials.getPubId(), userPubId, connectorCode, validationResult.identifier());

        return integrationCredentials;
    }

    public List<IntegrationCredentials> getIntegrations(UUID userPubId) {
        return integrationCredentialsRepository.findByUserPubIdNotDeleted(userPubId);
    }

    public IntegrationCredentials getIntegration(UUID pubId, UUID userPubId) {
        return integrationCredentialsRepository.findByPubIdNotDeleted(pubId)
                .filter(i -> i.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Integration not found"));
    }

    @Transactional
    public void deleteIntegration(UUID pubId, UUID userPubId) {
        IntegrationCredentials integrationCredentials = getIntegration(pubId, userPubId);

        // Remove webhook if platform supports it
        try {
            var handler = platformRegistry.getHandler(integrationCredentials.extractPlatformCode());
            Map<String, String> credentials = encryptionService.decryptCredentials(integrationCredentials.getEncryptedData());
            handler.removeWebhook(credentials);
        } catch (Exception e) {
            log.warn("Failed to remove webhook for integration {}: {}", pubId, e.getMessage());
        }

        integrationCredentialsRepository.softDelete(integrationCredentials.getId(), LocalDateTime.now());
        log.info("Deleted integration {}", pubId);
    }

    @Transactional
    public IntegrationCredentials updateCredentials(UUID pubId, UUID userPubId, Map<String, String> credentials) {
        IntegrationCredentials integrationCredentials = getIntegration(pubId, userPubId);
        var handler = platformRegistry.getHandler(integrationCredentials.extractPlatformCode());

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
            String webhookUrl = webhookBaseUrl + "/webhook/integration/" + integrationCredentials.getPubId();
            handler.setupWebhook(integrationCredentials, credentials, webhookUrl);
        }

        return integrationCredentialsRepository.save(integrationCredentials);
    }

    @Transactional
    public IntegrationCredentials updateIntegration(UUID pubId, UUID userPubId, Boolean enabled, String name) {
        IntegrationCredentials integrationCredentials = getIntegration(pubId, userPubId);

        if (enabled != null) {
            integrationCredentials.setEnabled(enabled);
        }
        if (name != null) {
            integrationCredentials.setName(name);
        }

        return integrationCredentialsRepository.save(integrationCredentials);
    }
}
