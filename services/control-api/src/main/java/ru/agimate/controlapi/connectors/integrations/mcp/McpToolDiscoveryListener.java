package ru.agimate.controlapi.connectors.integrations.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.agimate.controlapi.connectors.core.events.ConnectorCreatedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorDeletedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorModifiedEvent;
import ru.agimate.controlapi.database.entities.ConnectionTool;

import java.util.List;
import java.util.UUID;

/**
 * Sync of the {@code connection_tools} cache on lifecycle events of MCP instances (the analogue of
 * {@code ConnectorIdentityListener} for jobs): on create/modify — rediscovery of the tools
 * ({@code tools/list} → rewrite), on delete — cleanup of the rows by connectionId.
 *
 * <p>{@link TransactionPhase#AFTER_COMMIT} — the sync must not happen if the transaction creating or
 * modifying the integration rolled back (and the id is not assigned yet at
 * {@code validateCredentials} time). A {@code tools/list} failure (the server is down) does not break
 * the lifecycle — we log a warning, and the cache catches up on a manual refresh or the next modify.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolDiscoveryListener {

    private final McpToolDiscoveryService mcpToolDiscoveryService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreated(ConnectorCreatedEvent event) {
        syncIfMcp(event.connectorCode(), event.connectionId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onModified(ConnectorModifiedEvent event) {
        syncIfMcp(event.connectorCode(), event.connectionId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDeleted(ConnectorDeletedEvent event) {
        if (!McpConnectorService.CONNECTOR_CODE.equals(event.connectorCode())) {
            return;
        }
        int removed = mcpToolDiscoveryService.deleteByConnectionId(UUID.fromString(event.connectionId()));
        if (removed > 0) {
            log.info("Removed {} MCP tool row(s) for {}", removed, event.connectionId());
        }
    }

    private void syncIfMcp(String connectorCode, String connectionId) {
        if (!McpConnectorService.CONNECTOR_CODE.equals(connectorCode)) {
            return;
        }
        log.info("Discovering MCP tools for {}", connectionId);
        try {
            UUID identityId = UUID.fromString(connectionId);
            List<ConnectionTool> fresh = mcpToolDiscoveryService.discover(identityId);
            if (fresh != null) {
                mcpToolDiscoveryService.reconcile(identityId, fresh);
            }
        } catch (Exception e) {
            // the full stack — getMessage() is null on some exceptions and hides the cause
            log.warn("MCP tool discovery failed for {}", connectionId, e);
        }
    }
}
