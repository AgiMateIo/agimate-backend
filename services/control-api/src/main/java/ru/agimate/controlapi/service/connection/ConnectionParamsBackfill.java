package ru.agimate.controlapi.service.connection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ru.agimate.controlapi.connectors.core.ConnectionCredentialStore;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;

import java.util.List;

/**
 * Moves the open credential fields of connections created before {@code connections.params} out of
 * their secrets — Liquibase cannot decrypt one. Only connectors that declare an open field are looked
 * at, and a row that has been split has non-empty params, so after one pass the query finds nothing
 * and the next startups decrypt nothing — save the rare URL that carries a key and so stays secret. Delete once every environment has run it
 * (docs/decisions/skills-for-connection.md).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectionParamsBackfill {

    private final ConnectorRegistry connectorRegistry;
    private final ConnectionRepository connectionRepository;
    private final ConnectionCredentialStore credentialStore;
    private final TransactionTemplate transactionTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        List<String> codes = connectorRegistry.getHandlers().stream()
                .filter(IntegrationConnectorHandler.class::isInstance)
                .map(IntegrationConnectorHandler.class::cast)
                .filter(handler -> handler.getCredentialFields().values().stream().anyMatch(field -> !field.secret()))
                .map(IntegrationConnectorHandler::connectorCode)
                .toList();
        if (codes.isEmpty()) {
            return;
        }
        int moved = 0;
        for (Connection connection : connectionRepository.findWithoutParams(codes)) {
            try {
                // One transaction per row: a secret that fails to decrypt must not hold back the rest.
                transactionTemplate.executeWithoutResult(status -> {
                    Connection managed = connectionRepository.findById(connection.getId()).orElseThrow();
                    credentialStore.write(managed, credentialStore.read(managed));
                });
                moved++;
            } catch (Exception e) {
                log.error("Failed to move open credential fields of connection {}: {}", connection.getId(), e.getMessage());
            }
        }
        if (moved > 0) {
            log.info("Moved open credential fields of {} connection(s) out of their secrets", moved);
        }
    }
}
