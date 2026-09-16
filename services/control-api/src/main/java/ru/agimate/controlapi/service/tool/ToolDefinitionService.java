package ru.agimate.controlapi.service.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvFactory;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.ToolProvider;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.ConnectionToolMapper;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.enums.DefinitionBinding;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The single place that lists an instance's tools — the source is decided by {@code definitionBinding}:
 * STATIC → the handler's {@link ToolProvider}; DYNAMIC → {@code connection_tools} by connectionId. Every
 * listing goes through {@link #getTools(Connection, ConnectorEnv)}: the run context, the MCP surface, the
 * agent's available names, channel validation and the HTTP listings, so the branching and the cache read
 * live here once.
 *
 * <p>What differs between callers stays with them. The HTTP listings are owner-scoped here
 * ({@code connectionId} must belong to {@code userId}, otherwise it is an IDOR); callers that already
 * hold the connection pass it together with the env they list under — the run context lists a
 * session-aware connector under its session.
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

    /**
     * The tools of a connection the caller already holds — no owner check. Empty for a connector that
     * exposes no tool definitions at all (a pure channel).
     */
    public Map<String, ConnectorToolSpec> getTools(Connection connection, ConnectorEnv env) {
        return connectorRepository.findById(connection.getConnectorCode())
                .filter(connector -> connector.getDefinitionBinding() != null)
                .map(connector -> toolsOf(connector, connection.getId(), env))
                .orElseGet(Map::of);
    }

    /** Owner-scoped listing for the HTTP surfaces; a STATIC connector may be listed without an instance. */
    public Map<String, ConnectorToolSpec> getTools(UUID userId, String connectorCode, UUID connectionId) {
        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));
        if (connector.getDefinitionBinding() == null) {
            throw new BadRequestStatusException("Connector does not expose tool definitions: " + connectorCode);
        }
        if (connector.getDefinitionBinding() == DefinitionBinding.DYNAMIC) {
            if (connectionId == null) {
                throw new BadRequestStatusException("This connector requires an instance connectionId (connectionId)");
            }
            // Ownership scope: the instance must belong to the caller (otherwise it is an IDOR).
            connectionRepository.findByIdAndUserIdNotDeleted(connectionId, userId)
                    .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + connectionId));
        }
        return toolsOf(connector, connectionId, ConnectorEnvFactory.listing(connectionId));
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
        if (connector.getDefinitionBinding() == null) {
            throw new BadRequestStatusException("Connector does not expose tool definitions: " + connectorCode);
        }
        return toolsOf(connector, null, ConnectorEnvFactory.listing(null));
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

    /**
     * STATIC without a {@link ToolProvider} is a legitimate channel connector (webchat/acp): an empty set.
     * DYNAMIC without an instance has no type-level tools. A DYNAMIC connector is read from the cache
     * rather than through its provider because connected apps have none — their tools are written by
     * the app link.
     */
    private Map<String, ConnectorToolSpec> toolsOf(Connector connector, UUID connectionId, ConnectorEnv env) {
        return switch (connector.getDefinitionBinding()) {
            case STATIC -> connectorRegistry.findCapability(connector.getCode(), ToolProvider.class)
                    .map(provider -> provider.getTools(env))
                    .orElseGet(Map::of);
            case DYNAMIC -> connectionId == null ? Map.of() : cachedTools(connectionId);
        };
    }

    private Map<String, ConnectorToolSpec> cachedTools(UUID connectionId) {
        Map<String, ConnectorToolSpec> tools = new LinkedHashMap<>();
        connectionToolRepository.findActiveByConnectionId(connectionId)
                .forEach(tool -> tools.put(tool.getName(), ConnectionToolMapper.toSpec(tool)));
        return tools;
    }
}
