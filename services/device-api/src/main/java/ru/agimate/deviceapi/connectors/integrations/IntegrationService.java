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
import ru.agimate.deviceapi.database.entities.ConnectorRegistry;
import ru.agimate.deviceapi.database.entities.IntegrationCredentials;
import ru.agimate.deviceapi.database.entities.Platform;
import ru.agimate.deviceapi.database.enums.ConnectorType;
import ru.agimate.deviceapi.database.repositories.ConnectorRegistryRepository;
import ru.agimate.deviceapi.database.repositories.IntegrationCredentialsRepository;
import ru.agimate.deviceapi.database.repositories.PlatformRepository;
import ru.agimate.deviceapi.service.ConnectorService;

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
    private final ConnectorRegistryRepository connectorRegistryRepository;
    private final PlatformRepository platformRepository;
    private final IntegrationPlatformRegistry platformRegistry;
    private final IntegrationEncryptionService encryptionService;
    private final ConnectorService connectorService;

    @Transactional
    public IntegrationCredentials createIntegration(
            UUID userPubId,
            String platformCode,
            Map<String, String> credentials,
            String name
    ) {
        Platform platform = platformRepository.findByCode(platformCode)
                .orElseThrow(() -> new BadRequestStatusException("Unknown platform: " + platformCode));

        var handler = platformRegistry.getHandler(platform.getCode());

        var validationResult = handler.validateCredentials(credentials);
        if (!validationResult.valid()) {
            throw new ValidationErrorStatusException(validationResult.errorField(), validationResult.errorMessage());
        }

        // Check for duplicate
        integrationCredentialsRepository.findByUserPubIdNotDeleted(userPubId).stream()
                .filter(i -> i.getPlatform().getCode().equals(platform.getCode())
                        && i.getPlatformIdentifier().equals(validationResult.identifier()))
                .findFirst()
                .ifPresent(existing -> {
                    throw new ConflictStatusException("Integration already exists for " + platform.getCode() + ": " + validationResult.identifier());
                });

        // Create connector registry entry for this integration
        String connectorName = name != null ? name
                : validationResult.displayName() != null ? validationResult.displayName()
                : platform.getCode() + ": " + validationResult.identifier();

        ConnectorRegistry registry = ConnectorRegistry.builder()
                .code(platformCode + ":" + validationResult.identifier())
                .type(ConnectorType.INTEGRATION)
                .title(connectorName)
                .description("Integration: " + platform.getCode())
                .userPubId(userPubId)
                .build();
        registry = connectorRegistryRepository.save(registry);

        // Create app with capabilities for this integration
        var app = connectorService.createAppWithCapabilities(
                userPubId, connectorName, "Integration: " + platform.getCode(),
                registry.getId(),
                handler.getPredefinedTriggers(), handler.getPredefinedTools()
        );

        // Encrypt credentials
        String encryptedData = encryptionService.encryptCredentials(credentials);

        // Generate webhook secret only if platform supports webhooks
        String webhookSecret = platform.getSupportsWebhooks()
                ? encryptionService.generateSecureToken()
                : null;

        IntegrationCredentials integrationCredentials = IntegrationCredentials.builder()
                .connectorRegistryId(registry.getId())
                .platform(platform)
                .userPubId(userPubId)
                .name(name)
                .platformIdentifier(validationResult.identifier())
                .encryptedData(encryptedData)
                .webhookSecret(webhookSecret)
                .build();

        integrationCredentials = integrationCredentialsRepository.save(integrationCredentials);

        // Setup webhook only if platform supports it
        if (platform.getSupportsWebhooks()) {
            String webhookUrl = webhookBaseUrl + "/webhook/integration/" + integrationCredentials.getPubId();
            try {
                handler.setupWebhook(integrationCredentials, credentials, webhookUrl);
            } catch (Exception e) {
                log.error("Failed to setup webhook for integration {}, rolling back", integrationCredentials.getPubId(), e);
                integrationCredentialsRepository.delete(integrationCredentials);
                connectorService.deleteConnector(app.getPubId(), userPubId);
                throw new BadRequestStatusException("Failed to setup webhook");
            }
        }

        log.info("Created integration {} for user {}: {} ({})",
                integrationCredentials.getPubId(), userPubId, platform.getCode(), validationResult.identifier());

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
            var handler = platformRegistry.getHandler(integrationCredentials.getPlatform().getCode());
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
        var handler = platformRegistry.getHandler(integrationCredentials.getPlatform().getCode());

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
        if (integrationCredentials.getPlatform().getSupportsWebhooks()) {
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
