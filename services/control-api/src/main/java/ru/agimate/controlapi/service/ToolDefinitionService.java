package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.controller.agent.dto.AppToolMapper;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.repositories.AppRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ToolDefinitionService {

    private final ConnectorRepository connectorRepository;
    private final ConnectorRegistry connectorRegistry;
    private final AppRepository appRepository;

    public Map<String, ConnectorToolSpec> getTools(UUID userId, String connectorCode, UUID identity) {
        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));

        return switch (connector.getType()) {
            case INTEGRATION, INTERNAL_SERVICE -> connectorRegistry.findHandler(connectorCode)
                    .orElseThrow(() -> new BadRequestStatusException("Unsupported connector: " + connectorCode))
                    .getTools();
            case APP -> AppToolMapper.fromAppTools(resolveApp(userId, connectorCode, identity).getTools());
            case LOOPBACK -> throw new BadRequestStatusException(
                    "Connector type " + connector.getType() + " does not expose static tool definitions");
        };
    }

    public ConnectorToolSpec getTool(UUID userId, String connectorCode, String toolName, UUID identity) {
        ConnectorToolSpec tool = getTools(userId, connectorCode, identity).get(toolName);
        if (tool == null) {
            throw new NotFoundStatusException("Tool not found: " + toolName);
        }
        return tool;
    }

    private App resolveApp(UUID userId, String connectorCode, UUID identity) {
        if (identity == null) {
            throw new BadRequestStatusException("identity is required for APP connectors");
        }
        App app = appRepository.findByIdAndUserIdNotDeleted(identity, userId)
                .orElseThrow(() -> new NotFoundStatusException("App not found"));
        if (!Objects.equals(app.getConnectorCode(), connectorCode)) {
            throw new BadRequestStatusException("App does not belong to connector " + connectorCode);
        }
        return app;
    }
}
