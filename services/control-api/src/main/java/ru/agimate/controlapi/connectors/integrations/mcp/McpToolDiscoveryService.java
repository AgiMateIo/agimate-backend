package ru.agimate.controlapi.connectors.integrations.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.service.secret.SecretService;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.ConnectionTool;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.SecretRepository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Discovery and caching of an MCP instance's tools in {@code connection_tools}. The network
 * {@code tools/list} ({@link #discover}) is deliberately separated from the database write
 * ({@link #reconcile}) so a transaction is not held open across a network call — both methods are
 * public and are called from {@link McpToolDiscoveryListener} (and the manage refresh) through the
 * proxy, so the {@code @Transactional} on {@link #reconcile} does apply.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpToolDiscoveryService {

    private final ConnectionRepository connectionRepository;
    private final SecretRepository secretRepository;
    private final SecretService secretService;
    private final McpClient mcpClient;
    private final ConnectionToolRepository connectionToolRepository;

    /**
     * Fetches the instance's tools from the server (network, outside a transaction). {@code null} —
     * the instance was not found or is not an MCP connector (there is nothing to sync).
     */
    public List<ConnectionTool> discover(UUID connectionId) {
        Connection connection = connectionRepository.findByIdNotDeleted(connectionId).orElse(null);
        if (connection == null || !McpConnectorService.CONNECTOR_CODE.equals(connection.getConnectorCode())) {
            return null;
        }
        Map<String, String> decrypted = revealCredentials(connection);
        McpClient.ServerConfig config = McpUtils.toServerConfig(decrypted);
        List<JsonNode> rawTools = mcpClient.listTools(config);
        return rawTools.stream()
                .map(tool -> McpToolMapper.toEntity(connectionId, tool))
                .filter(Objects::nonNull)
                .toList();
    }

    /** Rewrites the instance's {@code connection_tools} cache: upsert by name plus deletion of what disappeared. */
    @Transactional
    public void reconcile(UUID connectionId, List<ConnectionTool> fresh) {
        Map<String, ConnectionTool> existing = new HashMap<>();
        connectionToolRepository.findActiveByConnectionId(connectionId)
                .forEach(tool -> existing.put(tool.getName(), tool));

        Set<String> freshNames = new HashSet<>();
        for (ConnectionTool tool : fresh) {
            freshNames.add(tool.getName());
            ConnectionTool row = existing.get(tool.getName());
            if (row == null) {
                connectionToolRepository.save(tool);
            } else {
                row.setTitle(tool.getTitle());
                row.setDescription(tool.getDescription());
                row.setInputSchema(tool.getInputSchema());
                row.setOutputSchema(tool.getOutputSchema());
                row.setAnnotations(tool.getAnnotations());
                connectionToolRepository.save(row);
            }
        }

        existing.values().stream()
                .filter(tool -> !freshNames.contains(tool.getName()))
                .forEach(connectionToolRepository::delete);

        log.info("Synced {} MCP tool(s) for connection {}", freshNames.size(), connectionId);
    }

    @Transactional
    public int deleteByConnectionId(UUID connectionId) {
        return connectionToolRepository.deleteByConnectionId(connectionId);
    }

    private Map<String, String> revealCredentials(Connection connection) {
        if (connection.getSecretId() == null) {
            return Map.of();
        }
        Secret secret = secretRepository.findById(connection.getSecretId()).orElse(null);
        return secret == null ? Map.of() : secretService.reveal(secret, connection.getId());
    }
}
