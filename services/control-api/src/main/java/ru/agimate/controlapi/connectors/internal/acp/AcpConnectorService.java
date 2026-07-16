package ru.agimate.controlapi.connectors.internal.acp;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;
import ru.agimate.controlapi.database.enums.ExecutionLocus;
import ru.agimate.controlapi.database.enums.IdentityScope;
import ru.agimate.controlapi.database.enums.DefinitionBinding;
import ru.agimate.controlapi.database.enums.TransportDirection;
import ru.agimate.controlapi.database.model.ConnectorTraits;
import ru.agimate.controlapi.service.acp.AcpSessionRegistry;
import ru.agimate.controlapi.service.channel.handler.AcpChannelHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Фасад ACP-коннектора (Agent Client Protocol): диалог с агентом из IDE (Zed и другие
 * ACP-клиенты) через WebSocket-эндпоинт {@code /acp}.
 *
 * <p>Устройство повторяет webchat: одна connection на пользователя ({@code scope = USER},
 * материализуется binding'ом при первом {@code session/new}), каналы — per-agent. Входящие
 * шлёт {@code AcpService} (триггер {@code message_received} с явными sessionId/audience),
 * доставка ответов — {@code AcpChannelHandler} (JSON-RPC {@code session/update} в живое
 * соединение).
 *
 * <p>Тулы IDE ({@link AcpToolService}: read_file/write_file/run_command) исполняются обратным
 * JSON-RPC в живое соединение сессии — {@code DefinitionBinding.STATIC}, {@code ExecutionLocus.BACKEND}
 * (control-api диспатчит вызов, но само действие делает клиент).
 *
 * <p>Плюс session-scoped MCP-тулы, проброшенные из IDE (мост поднял MCP-серверы Zed и сделал
 * {@code tools/list}): в контекст рана они подмешиваются через {@link #getTools(ConnectorEnv)}
 * (фиксированные + {@code AcpSessionRegistry.mcpToolSpecs(sessionId)}), а исполняются через
 * {@link #executeTool} обратным {@code mcp/call_tool} ({@link AcpToolService#callMcpTool}). ABAC
 * применяется к ним по имени как к любым тулам коннектора (default-allow, DENY-политикой можно закрыть).
 */
@Component
public class AcpConnectorService extends BaseConnectorHandler
        implements InternalConnectorHandler, TriggerProvider {

    private final AcpToolService acpToolService;
    private final AcpSessionRegistry sessionRegistry;

    public AcpConnectorService(AcpToolService toolService, AcpSessionRegistry sessionRegistry) {
        super(toolService);
        this.acpToolService = toolService;
        this.sessionRegistry = sessionRegistry;
    }

    /** Фиксированные тулы (read_file/write_file/run_command) + session-scoped MCP-тулы этой IDE-сессии. */
    @Override
    public Map<String, ConnectorToolSpec> getTools(ConnectorEnv env) {
        Map<String, ConnectorToolSpec> fixed = getTools();
        UUID sessionId = env.sessionId();
        if (sessionId == null) {
            return fixed;
        }
        Map<String, ConnectorToolSpec> mcp = sessionRegistry.mcpToolSpecs(sessionId);
        if (mcp.isEmpty()) {
            return fixed;
        }
        Map<String, ConnectorToolSpec> merged = new LinkedHashMap<>(fixed);
        merged.putAll(mcp);
        return merged;
    }

    /** Фиксированный @Tool → reflection-диспатч базы; иначе — MCP-тул сессии → обратный mcp/call_tool. */
    @Override
    public Map<String, Object> executeTool(ConnectorEnv env, String toolName, Map<String, Object> args) {
        if (getTools().containsKey(toolName)) {
            return super.executeTool(env, toolName, args);
        }
        return acpToolService.callMcpTool(env.sessionId(), toolName, args);
    }

    @Override
    public String connectorCode() {
        return AcpChannelHandler.CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "ACP (IDE)";
    }

    /** Одна connection на пользователя: все его агенты делят её через binding'и. */
    @Override
    public ConnectorTraits traits() {
        return new ConnectorTraits(
                TransportDirection.OUTBOUND, ExecutionLocus.BACKEND, DefinitionBinding.STATIC,
                List.of(IdentityScope.USER));
    }

    @Override
    public Map<String, TriggerSpec> getTriggers() {
        return Map.of(AcpChannelHandler.TRIGGER_MESSAGE_RECEIVED, new TriggerSpec(
                "Message from the user typed in the IDE (ACP client)",
                List.of("sessionId", "messageId", "text")));
    }
}
