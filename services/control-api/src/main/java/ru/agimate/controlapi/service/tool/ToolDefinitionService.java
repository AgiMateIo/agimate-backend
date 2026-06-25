package ru.agimate.controlapi.service.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorContext;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.integrations.mcp.McpToolMapper;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Единое место листинга тулов экземпляра — источник определяется {@code toolBinding}:
 * STATIC → рефлексия handler'а ({@code getTools(ctx)}); DYNAMIC → {@code connection_tools} по identity.
 * Сюда же делегируют agent- и gRPC-листинги, чтобы не дублировать ветвление.
 *
 * <p>DYNAMIC-листинг скоупится по владельцу: {@code identity} (= connections.id) проверяется на
 * принадлежность {@code userId} — иначе IDOR (чужой экземпляр). STATIC-набор — определения уровня
 * типа коннектора, не привязаны к владельцу.
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

    public Map<String, ConnectorToolSpec> getTools(UUID userId, String connectorCode, UUID identity) {
        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));

        return switch (connector.getToolBinding()) {
            case STATIC -> connectorRegistry.findHandler(connectorCode)
                    .orElseThrow(() -> new BadRequestStatusException("Unsupported connector: " + connectorCode))
                    .getTools(listingContext(identity));
            case DYNAMIC -> dynamicTools(userId, identity);
            case null -> throw new BadRequestStatusException(
                    "Connector does not expose tool definitions: " + connectorCode);
        };
    }

    public ConnectorToolSpec getTool(UUID userId, String connectorCode, String toolName, UUID identity) {
        ConnectorToolSpec tool = getTools(userId, connectorCode, identity).get(toolName);
        if (tool == null) {
            throw new NotFoundStatusException("Tool not found: " + toolName);
        }
        return tool;
    }

    /** Тулы динамического экземпляра из {@code connection_tools}; identity проверяется на владельца. */
    private Map<String, ConnectorToolSpec> dynamicTools(UUID userId, UUID identity) {
        if (identity == null) {
            return Map.of();
        }
        // Ownership-скоуп: экземпляр должен принадлежать вызывающему (иначе IDOR).
        connectionRepository.findByIdAndUserIdNotDeleted(identity, userId)
                .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + identity));
        Map<String, ConnectorToolSpec> tools = new LinkedHashMap<>();
        connectionToolRepository.findActiveByConnectionId(identity)
                .forEach(tool -> tools.put(tool.getName(), McpToolMapper.toSpec(tool)));
        return tools;
    }

    private static ConnectorContext listingContext(UUID identity) {
        return new ConnectorContext(identity == null ? null : identity.toString(),
                null, null, null, Map.of(), null);
    }
}
