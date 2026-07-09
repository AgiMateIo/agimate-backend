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
 * Синк кэша {@code connection_tools} по lifecycle-событиям MCP-экземпляров (аналог
 * {@code ConnectorIdentityListener} для тасок): на create/modify — ре-дискавери тулов
 * ({@code tools/list} → перезапись), на delete — чистка строк по connectionId.
 *
 * <p>{@link TransactionPhase#AFTER_COMMIT} — синк не должен случиться, если транзакция создания/
 * изменения интеграции откатилась (и id ещё не присвоен на момент {@code validateCredentials}).
 * Сбой {@code tools/list} (сервер недоступен) не валит lifecycle — логируем warn, кэш досинкается
 * ручным refresh или следующим modify.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolDiscoveryListener {

    private final McpToolService mcpToolService;

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
        int removed = mcpToolService.deleteByConnectionId(UUID.fromString(event.connectionId()));
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
            List<ConnectionTool> fresh = mcpToolService.discover(identityId);
            if (fresh != null) {
                mcpToolService.reconcile(identityId, fresh);
            }
        } catch (Exception e) {
            // полный стек — getMessage() у части исключений null и прячет причину
            log.warn("MCP tool discovery failed for {}", connectionId, e);
        }
    }
}
