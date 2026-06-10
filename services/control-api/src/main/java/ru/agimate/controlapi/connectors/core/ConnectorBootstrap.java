package ru.agimate.controlapi.connectors.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.enums.ConnectorType;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;

/**
 * Бутстрап коннекторов при старте приложения:
 * <ol>
 *   <li>статические строки {@code connectors} ({@code app}, {@code claude-code}) — только если отсутствуют;</li>
 *   <li>upsert строки {@code connectors} для каждого handler'а из registry — код-источник истины
 *       для name/type/credential_fields; description/features не затираются.</li>
 * </ol>
 *
 * <p>Задачи коннекторов на старте не регистрируются: декларативные таски интеграций заводятся
 * по {@code ConnectorCreatedEvent} (добавление коннектора пользователем), динамические — агентом
 * через тулы (например {@code time.schedule}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorBootstrap {

    private final ConnectorRepository connectorRepository;
    private final ConnectorRegistry connectorRegistry;

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        saveIfAbsent(Connector.builder()
                .code("app")
                .type(ConnectorType.APP)
                .name("App")
                .build());
        saveIfAbsent(Connector.builder()
                .code("claude-code")
                .type(ConnectorType.LOOPBACK)
                .name("Claude Code")
                .build());

        for (ConnectorHandler handler : connectorRegistry.getHandlers()) {
            upsertConnector(handler);
        }
        log.info("Connectors bootstrapped: {}", connectorRegistry.getHandlers().size());
    }

    private void upsertConnector(ConnectorHandler handler) {
        Connector connector = connectorRepository.findById(handler.connectorCode())
                .orElseGet(() -> Connector.builder()
                        .code(handler.connectorCode())
                        .build());

        connector.setType(handler instanceof IntegrationConnectorHandler
                ? ConnectorType.INTEGRATION
                : ConnectorType.INTERNAL_SERVICE);
        connector.setName(handler.connectorName());
        connector.setCredentialFields(handler instanceof IntegrationConnectorHandler integration
                ? integration.getCredentialFields()
                : null);

        connectorRepository.save(connector);
    }

    private void saveIfAbsent(Connector connector) {
        if (!connectorRepository.existsById(connector.getCode())) {
            connectorRepository.save(connector);
            log.info("Created connector: {} ({})", connector.getCode(), connector.getType());
        }
    }
}
