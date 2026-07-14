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
 * Дискавери и кэш тулов MCP-экземпляра в {@code connection_tools}. Сетевой {@code tools/list}
 * ({@link #discover}) намеренно отделён от записи в БД ({@link #reconcile}), чтобы не держать
 * транзакцию открытой на время сетевого вызова — оба метода публичные и вызываются из
 * {@link McpToolDiscoveryListener} (и manage-refresh) через прокси, поэтому {@code @Transactional}
 * на {@link #reconcile} применяется.
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
     * Снимает тулы экземпляра с сервера (сеть, вне транзакции). {@code null} — экземпляр не найден
     * или это не MCP-коннектор (нечего синкать).
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

    /** Перезаписывает кэш {@code connection_tools} экземпляра: upsert по имени + удаление пропавших. */
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
