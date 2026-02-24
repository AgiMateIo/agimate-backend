package ru.agimate.deviceapi.integration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.ValidationErrorStatusException;
import ru.agimate.deviceapi.database.entities.Integration;
import ru.agimate.deviceapi.database.entities.Platform;
import ru.agimate.deviceapi.database.repositories.IntegrationRepository;
import ru.agimate.deviceapi.database.repositories.PlatformRepository;
import ru.agimate.deviceapi.service.AppService;

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

    private final IntegrationRepository integrationRepository;
    private final PlatformRepository platformRepository;
    private final IntegrationPlatformRegistry platformRegistry;
    private final IntegrationEncryptionService encryptionService;
    private final AppService appService;

    @Transactional
    public Integration createIntegration(
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
        integrationRepository.findByUserPubIdNotDeleted(userPubId).stream()
                .filter(i -> i.getPlatform().getCode().equals(platform.getCode())
                        && i.getPlatformIdentifier().equals(validationResult.identifier()))
                .findFirst()
                .ifPresent(existing -> {
                    throw new ConflictStatusException("Integration already exists for " + platform.getCode() + ": " + validationResult.identifier());
                });

        // Create App for this integration
        String appName = name != null ? name
                : validationResult.displayName() != null ? validationResult.displayName()
                : platform.getCode() + ": " + validationResult.identifier();
        var app = appService.createAppForIntegration(
                userPubId, appName, "Integration: " + platform.getCode(),
                handler.getPredefinedTriggers(), handler.getPredefinedTools()
        );

        // Encrypt credentials
        String encryptedData = encryptionService.encryptCredentials(credentials);

        // Generate webhook secret only if platform supports webhooks
        String webhookSecret = platform.getSupportsWebhooks()
                ? encryptionService.generateSecureToken()
                : null;

        Integration integration = Integration.builder()
                .app(app)
                .platform(platform)
                .userPubId(userPubId)
                .name(name)
                .platformIdentifier(validationResult.identifier())
                .encryptedData(encryptedData)
                .webhookSecret(webhookSecret)
                .build();

        integration = integrationRepository.save(integration);

        // Setup webhook only if platform supports it
        if (platform.getSupportsWebhooks()) {
            String webhookUrl = webhookBaseUrl + "/webhook/integration/" + integration.getPubId();
            try {
                handler.setupWebhook(integration, credentials, webhookUrl);
            } catch (Exception e) {
                log.error("Failed to setup webhook for integration {}, rolling back", integration.getPubId(), e);
                integrationRepository.delete(integration);
                appService.deleteKey(app.getPubId(), userPubId);
                throw new BadRequestStatusException("Failed to setup webhook");
            }
        }

        log.info("Created integration {} for user {}: {} ({})",
                integration.getPubId(), userPubId, platform.getCode(), validationResult.identifier());

        return integration;
    }

    public List<Integration> getIntegrations(UUID userPubId) {
        return integrationRepository.findByUserPubIdNotDeleted(userPubId);
    }

    public Integration getIntegration(UUID pubId, UUID userPubId) {
        return integrationRepository.findByPubIdNotDeleted(pubId)
                .filter(i -> i.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Integration not found"));
    }

    @Transactional
    public void deleteIntegration(UUID pubId, UUID userPubId) {
        Integration integration = getIntegration(pubId, userPubId);

        // Remove webhook if platform supports it
        try {
            var handler = platformRegistry.getHandler(integration.getPlatform().getCode());
            Map<String, String> credentials = encryptionService.decryptCredentials(integration.getEncryptedData());
            handler.removeWebhook(credentials);
        } catch (Exception e) {
            log.warn("Failed to remove webhook for integration {}: {}", pubId, e.getMessage());
        }

        integrationRepository.softDelete(integration.getId(), LocalDateTime.now());
        appService.deleteKey(integration.getApp().getPubId(), userPubId);
        log.info("Deleted integration {}", pubId);
    }

    @Transactional
    public Integration updateCredentials(UUID pubId, UUID userPubId, Map<String, String> credentials) {
        Integration integration = getIntegration(pubId, userPubId);
        var handler = platformRegistry.getHandler(integration.getPlatform().getCode());

        var validationResult = handler.validateCredentials(credentials);
        if (!validationResult.valid()) {
            throw new ValidationErrorStatusException(validationResult.errorField(), validationResult.errorMessage());
        }

        // Ensure same platform identifier (same bot/account)
        if (!integration.getPlatformIdentifier().equals(validationResult.identifier())) {
            throw new BadRequestStatusException(
                    "Credentials belong to a different account: " + validationResult.identifier()
                            + " (expected: " + integration.getPlatformIdentifier() + ")");
        }

        // Re-encrypt
        integration.setEncryptedData(encryptionService.encryptCredentials(credentials));

        // Re-setup webhook if platform supports it
        if (integration.getPlatform().getSupportsWebhooks()) {
            String webhookUrl = webhookBaseUrl + "/webhook/integration/" + integration.getPubId();
            handler.setupWebhook(integration, credentials, webhookUrl);
        }

        return integrationRepository.save(integration);
    }

    @Transactional
    public Integration updateIntegration(UUID pubId, UUID userPubId, Boolean enabled, String name) {
        Integration integration = getIntegration(pubId, userPubId);

        if (enabled != null) {
            integration.setEnabled(enabled);
        }
        if (name != null) {
            integration.setName(name);
        }

        return integrationRepository.save(integration);
    }
}
