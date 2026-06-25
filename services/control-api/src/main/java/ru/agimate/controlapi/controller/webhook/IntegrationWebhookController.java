package ru.agimate.controlapi.controller.webhook;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.agimate.controlapi.connectors.core.ConnectorContext;
import ru.agimate.controlapi.connectors.core.ConnectorContextFactory;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping(IntegrationWebhookController.PATH)
@RequiredArgsConstructor
public class IntegrationWebhookController {

    public static final String PATH = "/webhook/integration";

    private final ConnectionRepository connectionRepository;
    private final ConnectorRegistry connectorRegistry;
    private final ConnectorContextFactory contextFactory;
    private final TriggerRouterService triggerRouterService;

    @PostMapping("/{integrationId}")
    public ResponseEntity<String> handleWebhook(
            @PathVariable UUID integrationId,
            @RequestBody String rawBody,
            HttpServletRequest request
    ) {
        var integrationOpt = connectionRepository.findByIdNotDeleted(integrationId);
        if (integrationOpt.isEmpty()) {
            log.warn("Webhook received for unknown integration: {}", integrationId);
            return ResponseEntity.ok("ok");
        }

        Connection integrationCredentials = integrationOpt.get();
        if (!integrationCredentials.isActive()) {
            log.debug("Webhook received for disabled integration: {}", integrationId);
            return ResponseEntity.ok("ok");
        }

        IntegrationConnectorHandler handler = connectorRegistry
                .findIntegrationHandler(integrationCredentials.getConnectorCode())
                .orElse(null);
        if (handler == null) {
            log.warn("Webhook received for integration without handler: {}", integrationId);
            return ResponseEntity.ok("ok");
        }

        // Guard: platform must support webhooks
        if (!handler.supportsWebhooks()) {
            log.warn("Webhook received for non-webhook platform: {}", integrationId);
            return ResponseEntity.notFound().build();
        }

        ConnectorContext context = contextFactory.forWebhook(integrationCredentials);

        if (!handler.validateWebhookRequest(context, request)) {
            log.warn("Webhook validation failed for integration: {}", integrationId);
            return ResponseEntity.ok("ok");
        }

        try {
            var trigger = handler.normalizeInbound(context, rawBody);
            triggerRouterService.routeWhTrigger(integrationCredentials.getUserId(), trigger);
        } catch (Exception e) {
            log.error("Failed to process webhook for integration {}: {}", integrationId, e.getMessage());
        }

        return ResponseEntity.ok("ok");
    }
}
