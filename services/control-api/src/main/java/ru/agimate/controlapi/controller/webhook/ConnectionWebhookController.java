package ru.agimate.controlapi.controller.webhook;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvFactory;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter;
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
    private final ConnectorEnvFactory envFactory;
    private final TriggerRouterService triggerRouterService;
    private final InboundRateLimiter rateLimiter;

    @PostMapping("/{connectionId}")
    public ResponseEntity<String> handleWebhook(
            @PathVariable UUID connectionId,
            @RequestBody String rawBody,
            HttpServletRequest request
    ) {
        // First of all, before touching the database: rejected flood must not cost a single query. We drop it
        // silently with a 200: the source is unauthenticated (we do not disclose the limits), and the platforms
        // (Telegram and the like) retry a non-2xx forever — a 429 would turn the flood self-sustaining.
        if (!rateLimiter.tryAcquire(InboundRateLimiter.Scope.TRIGGER, connectionId)) {
            log.warn("Webhook rate limit exceeded for connection: {}", connectionId);
            return ResponseEntity.ok("ok");
        }

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

        ConnectorEnv env = envFactory.forWebhook(connection);

        if (!handler.validateWebhookRequest(env, request)) {
            log.warn("Webhook validation failed for connection: {}", connectionId);
            return ResponseEntity.ok("ok");
        }

        try {
            var trigger = handler.normalizeInbound(env, rawBody);
            triggerRouterService.routeWhTrigger(connection.getUserId(), trigger);
        } catch (Exception e) {
            log.error("Failed to process webhook for connection {}: {}", connectionId, e.getMessage());
        }

        return ResponseEntity.ok("ok");
    }
}
