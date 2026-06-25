package ru.agimate.controlapi.connectors.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.model.ConnectorCapabilities;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;

/**
 * Бутстрап коннекторов при старте приложения:
 * <ol>
 *   <li>статические строки {@code connectors} ({@code app}, {@code claude-code}) — только если отсутствуют;</li>
 *   <li>upsert строки {@code connectors} для каждого handler'а из registry — код-источник истины
 *       для name/credential_fields/capabilities; description/features не затираются.</li>
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
        saveIfAbsent(buildStatic("app", "App", ConnectorCapabilities.device()));
        saveIfAbsent(buildStatic("claude-code", "Claude Code", ConnectorCapabilities.loopback()));

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

        connector.setName(handler.connectorName());
        connector.setCredentialFields(handler instanceof IntegrationConnectorHandler integration
                ? integration.getCredentialFields()
                : null);
        connector.applyCapabilities(handler.capabilities());

        connectorRepository.save(connector);
    }

    private static Connector buildStatic(String code, String name, ConnectorCapabilities capabilities) {
        Connector connector = Connector.builder().code(code).name(name).build();
        connector.applyCapabilities(capabilities);
        return connector;
    }

    private void saveIfAbsent(Connector connector) {
        if (!connectorRepository.existsById(connector.getCode())) {
            connectorRepository.save(connector);
            log.info("Created connector: {}", connector.getCode());
        }
    }
}
