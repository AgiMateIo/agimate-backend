package ru.agimate.controlapi.service.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorEnvFactory;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.ToolProvider;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.ConnectionToolMapper;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The single place that lists an instance's tools — the source is decided by {@code definitionBinding}:
 * STATIC → reflection over the handler ({@code getTools(ctx)}); DYNAMIC → {@code connection_tools} by
 * connectionId. Both the agent-facing and gRPC listings delegate here so the branching is not duplicated.
 *
 * <p>A DYNAMIC listing is scoped by owner: {@code connectionId} (= connections.id) is checked to belong
 * to {@code userId}, otherwise it is an IDOR (someone else's instance). A STATIC set consists of
 * connector-type-level definitions and is not tied to an owner.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ToolDefinitionService {

    private final ConnectorRepository connectorRepository;
    private final ConnectorRegistry connectorRegistry;
    private final ConnectionRepository connectionRepository;
    private final ConnectionToolRepository connectionToolRepository;

    public Map<String, ConnectorToolSpec> getTools(UUID userId, String connectorCode, UUID connectionId) {
        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));

        return switch (connector.getDefinitionBinding()) {
            // STATIC with no ToolProvider is a legitimate «channel» connector without tools (webchat/acp): an empty set.
            case STATIC -> connectorRegistry.findCapability(connectorCode, ToolProvider.class)
                    .map(provider -> provider.getTools(ConnectorEnvFactory.listing(connectionId)))
                    .orElseGet(Map::of);
            case DYNAMIC -> dynamicTools(userId, connectionId);
            case null -> throw new BadRequestStatusException(
                    "Connector does not expose tool definitions: " + connectorCode);
        };
    }

    public ConnectorToolSpec getTool(UUID userId, String connectorCode, String toolName, UUID connectionId) {
        ConnectorToolSpec tool = getTools(userId, connectorCode, connectionId).get(toolName);
        if (tool == null) {
            throw new NotFoundStatusException("Tool not found: " + toolName);
        }
        return tool;
    }

    /** Type-level (catalog) tools of a connector: STATIC → reflection; DYNAMIC → empty (no type tools). */
    public Map<String, ConnectorToolSpec> getCatalogTools(String connectorCode) {
        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));
        return switch (connector.getDefinitionBinding()) {
            case STATIC -> connectorRegistry.findCapability(connectorCode, ToolProvider.class)
                    .map(provider -> provider.getTools(ConnectorEnvFactory.listing(null)))
                    .orElseGet(Map::of);
            case DYNAMIC -> Map.of();
            case null -> throw new BadRequestStatusException(
                    "Connector does not expose tool definitions: " + connectorCode);
        };
    }

    /** Schema of a single catalog (type-level) tool. */
    public ConnectorToolSpec getCatalogTool(String connectorCode, String toolName) {
        ConnectorToolSpec tool = getCatalogTools(connectorCode).get(toolName);
        if (tool == null) {
            throw new NotFoundStatusException("Tool not found: " + toolName);
        }
        return tool;
    }

    /** Tools of a specific owned connection instance (connector code resolved from the connection). */
    public Map<String, ConnectorToolSpec> getConnectionTools(UUID userId, UUID connectionId) {
        Connection connection = connectionRepository.findByIdAndUserIdNotDeleted(connectionId, userId)
                .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + connectionId));
        return getTools(userId, connection.getConnectorCode(), connectionId);
    }

    /** Tools of a dynamic instance from {@code connection_tools}; connectionId is owner-checked. */
    private Map<String, ConnectorToolSpec> dynamicTools(UUID userId, UUID connectionId) {
        if (connectionId == null) {
            throw new BadRequestStatusException("This connector requires an instance connectionId (connectionId)");
        }
        // Ownership scope: the instance must belong to the caller (otherwise it is an IDOR).
        connectionRepository.findByIdAndUserIdNotDeleted(connectionId, userId)
                .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + connectionId));
        Map<String, ConnectorToolSpec> tools = new LinkedHashMap<>();
        connectionToolRepository.findActiveByConnectionId(connectionId)
                .forEach(tool -> tools.put(tool.getName(), ConnectionToolMapper.toSpec(tool)));
        return tools;
    }
}
