package ru.agimate.controlapi.connectors.integrations.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectionToolMapper;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.connectors.core.ToolProvider;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.IntegrationValidationResult;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A universal connector to a remote MCP server (Streamable HTTP). Unlike ordinary connectors its
 * tools are dynamic and per instance: each instance (a {@code connections} row = URL plus auth in
 * {@code secrets}) reports its own set through {@code tools/list}. So we implement
 * {@link ToolProvider} directly (without {@code BaseConnectorHandler} and {@code @Tool} methods):
 * <ul>
 *   <li>{@link #getTools()} — there are no static tools (empty);</li>
 *   <li>{@link #getTools(ConnectorEnv)} — the list from the {@code connection_tools} cache by
 *       {@code connectionId} (populated by {@link McpToolDiscoveryListener} on integration
 *       create/modify);</li>
 *   <li>{@link #executeTool} — proxying into {@code tools/call}.</li>
 * </ul>
 * MCP has no background jobs — {@code JobProvider} is not implemented.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpConnectorService implements IntegrationConnectorHandler, ToolProvider {

    public static final String CONNECTOR_CODE = McpUtils.CONNECTOR_CODE;

    private final McpClient mcpClient;
    private final ConnectionToolRepository connectionToolRepository;

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "MCP Server";
    }

    @Override
    public String connectorDescription() {
        return "Подключение к внешнему MCP-серверу: его тулы становятся доступны агенту как свои. "
                + "Набор тулов свой у каждого подключения — вычитывается с сервера при добавлении.";
    }

    @Override
    public ru.agimate.controlapi.database.model.ConnectorTraits traits() {
        return ru.agimate.controlapi.database.model.ConnectorTraits.dynamicIntegration();
    }

    @Override
    public Map<String, String> getCredentialFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(McpUtils.FIELD_URL, "Server URL (Streamable HTTP)");
        fields.put(McpUtils.FIELD_AUTH_TOKEN, "Bearer token (optional)");
        fields.put(McpUtils.FIELD_HEADERS, "Extra headers as JSON (optional)");
        return fields;
    }

    /**
     * Validation = the {@code initialize} handshake: it confirms the server is reachable and the auth
     * works. Tools are not persisted here — the instance's id is not assigned yet; they are synced by
     * {@link McpToolDiscoveryListener} after the commit. {@code identifier} = the server's URL (the
     * instance's canonical key).
     */
    @Override
    public IntegrationValidationResult validateCredentials(Map<String, String> credentials) {
        McpClient.ServerConfig config = McpUtils.toServerConfig(credentials);
        try {
            McpClient.ServerInfo info = mcpClient.probe(config);
            String label = info.name() != null && !info.name().isBlank() ? info.name() : host(config.url());
            return IntegrationValidationResult.success(config.url(), "MCP: " + label);
        } catch (ConnectorException e) {
            return IntegrationValidationResult.failure(McpUtils.FIELD_URL, e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to validate MCP server {}", config.url(), e);
            return IntegrationValidationResult.failure(McpUtils.FIELD_URL, "Failed to reach MCP server");
        }
    }

    /** MCP has no static tools — the set is always per instance, see {@link #getTools(ConnectorEnv)}. */
    @Override
    public Map<String, ConnectorToolSpec> getTools() {
        return Map.of();
    }

    /** The instance's tool list from the {@code connection_tools} cache; connectionId is {@code connections.id}. */
    @Override
    public Map<String, ConnectorToolSpec> getTools(ConnectorEnv env) {
        if (env == null || env.connectionId() == null) {
            return Map.of();
        }
        UUID connectionId;
        try {
            connectionId = UUID.fromString(env.connectionId());
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
        Map<String, ConnectorToolSpec> tools = new LinkedHashMap<>();
        connectionToolRepository.findActiveByConnectionId(connectionId)
                .forEach(tool -> tools.put(tool.getName(), ConnectionToolMapper.toSpec(tool)));
        return tools;
    }

    @Override
    public Map<String, Object> executeTool(ConnectorEnv env, String toolName, Map<String, Object> args) {
        McpClient.ServerConfig config = McpUtils.toServerConfig(env.credentials());
        return mcpClient.callTool(config, toolName, args);
    }

    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }
}
