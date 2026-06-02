package ru.agimate.deviceapi.service;

import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.connectors.integrations.IntegrationsRegistry;
import ru.agimate.deviceapi.connectors.internal.ServerSideToolRegistry;
import ru.agimate.deviceapi.controller.agent.dto.ToolSpecificationMapper;
import ru.agimate.deviceapi.controller.agent.dto.ToolSpecificationResponse;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.database.repositories.AppRepository;
import ru.agimate.deviceapi.database.repositories.ConnectorRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ToolDefinitionService {

    private final ConnectorRepository connectorRepository;
    private final IntegrationsRegistry integrationsRegistry;
    private final ServerSideToolRegistry serverSideToolRegistry;
    private final AppRepository appRepository;

    public Map<String, ToolSpecificationResponse> getTools(UUID userPubId, String connectorCode, UUID identity) {
        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));

        return switch (connector.getType()) {
            case INTEGRATION -> mapSpecs(integrationsRegistry.getHandler(connectorCode).getPredefinedTools());
            case INTERNAL_SERVICE -> mapSpecs(serverSideToolRegistry.getHandler(connectorCode).getToolDefinitions());
            case APP -> ToolSpecificationMapper.fromAppTools(resolveApp(userPubId, connectorCode, identity).getTools());
            case LOOPBACK -> throw new BadRequestStatusException(
                    "Connector type " + connector.getType() + " does not expose static tool definitions");
        };
    }

    public ToolSpecificationResponse getTool(UUID userPubId, String connectorCode, String toolName, UUID identity) {
        Map<String, ToolSpecificationResponse> tools = getTools(userPubId, connectorCode, identity);
        ToolSpecificationResponse tool = tools.get(toolName);
        if (tool == null) {
            throw new NotFoundStatusException("Tool not found: " + toolName);
        }
        return tool;
    }

    private App resolveApp(UUID userPubId, String connectorCode, UUID identity) {
        if (identity == null) {
            throw new BadRequestStatusException("identity is required for APP connectors");
        }
        App app = appRepository.findByIdAndUserPubIdNotDeleted(identity, userPubId)
                .orElseThrow(() -> new NotFoundStatusException("App not found"));
        if (!Objects.equals(app.getConnectorCode(), connectorCode)) {
            throw new BadRequestStatusException("App does not belong to connector " + connectorCode);
        }
        return app;
    }

    private static Map<String, ToolSpecificationResponse> mapSpecs(Map<String, ToolSpecification> specs) {
        Map<String, ToolSpecificationResponse> result = new LinkedHashMap<>();
        specs.forEach((name, spec) -> result.put(name, ToolSpecificationMapper.toResponse(spec)));
        return result;
    }
}
