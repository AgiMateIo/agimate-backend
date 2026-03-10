package ru.agimate.deviceapi.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.connectors.integrations.IntegrationsRegistry;
import ru.agimate.deviceapi.connectors.internal.ServerSideToolRegistry;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.database.enums.ConnectorType;
import ru.agimate.deviceapi.database.repositories.ConnectorRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class InitializationService {

    private final ConnectorRepository connectorRepository;
    private final IntegrationsRegistry integrationsRegistry;
    private final ServerSideToolRegistry serverSideToolRegistry;

    @PostConstruct
    public void init() {
        if (connectorRepository.count() == 0) {
            log.warn("Connectors db is empty. Have to add some of them");
            initializeConnectors();
        }
    }

    public void initializeConnectors() {
        // 1. APP connectors
        saveIfAbsent(Connector.builder()
                .code("app")
                .type(ConnectorType.APP)
                .name("App")
                .build());

        // 2. INTEGRATION connectors from registry
        for (var handler : integrationsRegistry.getAvailablePlatforms()) {
            saveIfAbsent(Connector.builder()
                    .code(handler.getPlatformCode())
                    .type(ConnectorType.INTEGRATION)
                    .name(handler.getPlatformName())
                    .credentialFields(handler.getCredentialFields())
                    .build());
        }

        // 3. INTERNAL_SERVICE connectors from registry
        for (var handler : serverSideToolRegistry.getAvailableHandlers()) {
            saveIfAbsent(Connector.builder()
                    .code(handler.getHandlerCode())
                    .type(ConnectorType.INTERNAL_SERVICE)
                    .name(handler.getHandlerCode())
                    .build());
        }

        // 4. LOOPBACK connectors
        saveIfAbsent(Connector.builder()
                .code("claude-code")
                .type(ConnectorType.LOOPBACK)
                .name("Claude Code")
                .build());

        log.info("Connectors initialized");
    }

    private void saveIfAbsent(Connector connector) {
        if (!connectorRepository.existsById(connector.getCode())) {
            connectorRepository.save(connector);
            log.info("Created connector: {} ({})", connector.getCode(), connector.getType());
        }
    }
}
