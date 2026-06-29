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
@RequestMapping(ConnectionWebhookController.PATH)
@RequiredArgsConstructor
public class ConnectionWebhookController {

    public static final String PATH = "/webhook";

    private final ConnectionRepository connectionRepository;
    private final ConnectorRegistry connectorRegistry;
    private final ConnectorContextFactory contextFactory;
    private final TriggerRouterService triggerRouterService;

    @PostMapping("/{connectionId}")
    public ResponseEntity<String> handleWebhook(
            @PathVariable UUID connectionId,
            @RequestBody String rawBody,
            HttpServletRequest request
    ) {
        var connectionOpt = connectionRepository.findByIdNotDeleted(connectionId);
        if (connectionOpt.isEmpty()) {
            log.warn("Webhook received for unknown connection: {}", connectionId);
            return ResponseEntity.ok("ok");
        }

        Connection connection = connectionOpt.get();
        if (!connection.isActive()) {
            log.debug("Webhook received for disabled connection: {}", connectionId);
            return ResponseEntity.ok("ok");
        }

        IntegrationConnectorHandler handler = connectorRegistry
                .findIntegrationHandler(connection.getConnectorCode())
                .orElse(null);
        if (handler == null) {
            log.warn("Webhook received for connection without integration handler: {}", connectionId);
            return ResponseEntity.ok("ok");
        }

        // Guard: platform must support webhooks
        if (!handler.supportsWebhooks()) {
            log.warn("Webhook received for non-webhook platform: {}", connectionId);
            return ResponseEntity.notFound().build();
        }

        ConnectorContext context = contextFactory.forWebhook(connection);

        if (!handler.validateWebhookRequest(context, request)) {
            log.warn("Webhook validation failed for connection: {}", connectionId);
            return ResponseEntity.ok("ok");
        }

        try {
            var trigger = handler.normalizeInbound(context, rawBody);
            triggerRouterService.routeWhTrigger(connection.getUserId(), trigger);
        } catch (Exception e) {
            log.error("Failed to process webhook for connection {}: {}", connectionId, e.getMessage());
        }

        return ResponseEntity.ok("ok");
    }
}
