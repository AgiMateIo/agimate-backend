package ru.agimate.deviceapi.controller.webhook;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.agimate.deviceapi.database.repositories.IntegrationCredentialsRepository;
import ru.agimate.deviceapi.connectors.integrations.IntegrationsRegistry;
import ru.agimate.deviceapi.service.TriggerRouterService;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping(IntegrationWebhookController.PATH)
@RequiredArgsConstructor
public class IntegrationWebhookController {

    public static final String PATH = "/webhook/integration";

    private final IntegrationCredentialsRepository integrationCredentialsRepository;
    private final IntegrationsRegistry platformRegistry;
    private final TriggerRouterService triggerRouterService;

    @PostMapping("/{integrationPubId}")
    public ResponseEntity<String> handleWebhook(
            @PathVariable UUID integrationPubId,
            @RequestBody String rawBody,
            HttpServletRequest request
    ) {
        var integrationOpt = integrationCredentialsRepository.findByPubIdNotDeleted(integrationPubId);
        if (integrationOpt.isEmpty()) {
            log.warn("Webhook received for unknown integration: {}", integrationPubId);
            return ResponseEntity.ok("ok");
        }

        var integrationCredentials = integrationOpt.get();
        if (!integrationCredentials.isActive()) {
            log.debug("Webhook received for disabled integration: {}", integrationPubId);
            return ResponseEntity.ok("ok");
        }

        var handler = platformRegistry.getHandler(integrationCredentials.extractPlatformCode());

        // Guard: platform must support webhooks
        if (!handler.supportsWebhooks()) {
            log.warn("Webhook received for non-webhook platform: {}", integrationPubId);
            return ResponseEntity.notFound().build();
        }

        if (!handler.validateWebhookRequest(integrationCredentials, request)) {
            log.warn("Webhook validation failed for integration: {}", integrationPubId);
            return ResponseEntity.ok("ok");
        }

        try {
            var triggerRequest = handler.normalizeInbound(integrationCredentials, rawBody);
            triggerRouterService.routeWhTrigger(integrationCredentials, triggerRequest);
        } catch (Exception e) {
            log.error("Failed to process webhook for integration {}: {}", integrationPubId, e.getMessage());
        }

        return ResponseEntity.ok("ok");
    }
}
