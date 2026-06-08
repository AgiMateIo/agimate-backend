package ru.agimate.controlapi.controller.webhook;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.agimate.controlapi.database.repositories.IntegrationCredentialsRepository;
import ru.agimate.controlapi.connectors.integrations.IntegrationsRegistry;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping(IntegrationWebhookController.PATH)
@RequiredArgsConstructor
public class IntegrationWebhookController {

    public static final String PATH = "/webhook/integration";

    private final IntegrationCredentialsRepository integrationCredentialsRepository;
    private final IntegrationsRegistry integrationsRegistry;
    private final TriggerRouterService triggerRouterService;

    @PostMapping("/{integrationId}")
    public ResponseEntity<String> handleWebhook(
            @PathVariable UUID integrationId,
            @RequestBody String rawBody,
            HttpServletRequest request
    ) {
        var integrationOpt = integrationCredentialsRepository.findByIdNotDeleted(integrationId);
        if (integrationOpt.isEmpty()) {
            log.warn("Webhook received for unknown integration: {}", integrationId);
            return ResponseEntity.ok("ok");
        }

        var integrationCredentials = integrationOpt.get();
        if (!integrationCredentials.isActive()) {
            log.debug("Webhook received for disabled integration: {}", integrationId);
            return ResponseEntity.ok("ok");
        }

        var integrationHandler = integrationsRegistry.getHandler(integrationCredentials.getConnectorCode());

        // Guard: platform must support webhooks
        if (!integrationHandler.supportsWebhooks()) {
            log.warn("Webhook received for non-webhook platform: {}", integrationId);
            return ResponseEntity.notFound().build();
        }

        if (!integrationHandler.validateWebhookRequest(integrationCredentials, request)) {
            log.warn("Webhook validation failed for integration: {}", integrationId);
            return ResponseEntity.ok("ok");
        }

        try {
            var trigger = integrationHandler.normalizeInbound(integrationCredentials, rawBody);
            triggerRouterService.routeWhTrigger(integrationCredentials, trigger);
        } catch (Exception e) {
            log.error("Failed to process webhook for integration {}: {}", integrationId, e.getMessage());
        }

        return ResponseEntity.ok("ok");
    }
}
