package ru.agimate.controlapi.connectors.integrations.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.connectors.integrations.IntegrationEncryptionService;
import ru.agimate.controlapi.database.entities.IntegrationCredentials;
import ru.agimate.controlapi.database.entities.McpTool;
import ru.agimate.controlapi.database.repositories.IntegrationCredentialsRepository;
import ru.agimate.controlapi.database.repositories.McpToolRepository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Дискавери и кэш тулов MCP-экземпляра. Сетевой {@code tools/list} ({@link #discover}) намеренно
 * отделён от записи в БД ({@link #reconcile}), чтобы не держать транзакцию открытой на время
 * сетевого вызова — оба метода публичные и вызываются из {@link McpToolDiscoveryListener} (и
 * manage-refresh) через прокси, поэтому {@code @Transactional} на {@link #reconcile} применяется.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpToolService {

    private final IntegrationCredentialsRepository credentialsRepository;
    private final IntegrationEncryptionService encryptionService;
    private final McpClient mcpClient;
    private final McpToolRepository mcpToolRepository;

    /**
     * Снимает тулы экземпляра с сервера (сеть, вне транзакции). {@code null} — экземпляр не найден
     * или это не MCP-коннектор (нечего синкать).
     */
    public List<McpTool> discover(UUID identityId) {
        IntegrationCredentials credentials = credentialsRepository.findByIdNotDeleted(identityId).orElse(null);
        if (credentials == null || !McpConnectorService.CONNECTOR_CODE.equals(credentials.getConnectorCode())) {
            return null;
        }
        Map<String, String> decrypted = encryptionService.decryptCredentials(credentials.getEncryptedData());
        McpClient.ServerConfig config = McpUtils.toServerConfig(decrypted);
        List<JsonNode> rawTools = mcpClient.listTools(config);
        return rawTools.stream()
                .map(tool -> McpToolMapper.toEntity(identityId, tool))
                .filter(Objects::nonNull)
                .toList();
    }

    /** Перезаписывает кэш {@code mcp_tool} экземпляра: upsert по имени + удаление пропавших тулов. */
    @Transactional
    public void reconcile(UUID identityId, List<McpTool> fresh) {
        Map<String, McpTool> existing = new HashMap<>();
        mcpToolRepository.findByIntegrationCredentialsId(identityId)
                .forEach(tool -> existing.put(tool.getName(), tool));

        Set<String> freshNames = new HashSet<>();
        for (McpTool tool : fresh) {
            freshNames.add(tool.getName());
            McpTool row = existing.get(tool.getName());
            if (row == null) {
                mcpToolRepository.save(tool);
            } else {
                row.setTitle(tool.getTitle());
                row.setDescription(tool.getDescription());
                row.setInputSchema(tool.getInputSchema());
                row.setOutputSchema(tool.getOutputSchema());
                row.setAnnotations(tool.getAnnotations());
                mcpToolRepository.save(row);
            }
        }

        existing.values().stream()
                .filter(tool -> !freshNames.contains(tool.getName()))
                .forEach(mcpToolRepository::delete);

        log.info("Synced {} MCP tool(s) for identity {}", freshNames.size(), identityId);
    }

    @Transactional
    public int deleteByIdentity(UUID identityId) {
        return mcpToolRepository.deleteByIntegrationCredentialsId(identityId);
    }
}
