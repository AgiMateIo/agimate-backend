package ru.agimate.controlapi.connectors.internal.acp;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.PromptBlockProvider;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.PromptBlock;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;
import ru.agimate.controlapi.service.acp.AcpSessionRegistry;
import ru.agimate.controlapi.service.channel.handler.AcpChannelHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Facade of the ACP connector (Agent Client Protocol): a dialogue with an agent from an IDE (Zed and
 * other ACP clients) over the WebSocket endpoint {@code /acp}.
 *
 * <p>The shape mirrors webchat: one connection per user ({@code scope = USER}, materialised by the
 * binding on the first {@code session/new}), and channels per agent. Incoming messages are sent by
 * {@code AcpService} (the trigger {@code message_received} with an explicit sessionId/audience), and
 * replies are delivered by {@code AcpChannelHandler} (a JSON-RPC {@code session/update} into the live
 * connection).
 *
 * <p>The IDE's tools ({@link AcpToolService}: read_file/write_file/run_command) are executed by a
 * reverse JSON-RPC call into the session's live connection — {@code DefinitionBinding.STATIC},
 * {@code ExecutionKind.BACKEND} (control-api dispatches the call, but the action itself is performed
 * by the client).
 *
 * <p>Plus session-scoped MCP tools forwarded from the IDE (the bridge started Zed's MCP servers and
 * did a {@code tools/list}): they are mixed into the run's context through
 * {@link #getTools(ConnectorEnv)} (the fixed ones plus
 * {@code AcpSessionRegistry.mcpToolSpecs(sessionId)}) and executed through {@link #executeTool} by a
 * reverse {@code mcp/call_tool} ({@link AcpToolService#callMcpTool}). ABAC applies to them by name
 * like to any of a connector's tools (default-allow, closable with a DENY policy).
 */
@Component
public class AcpConnectorService extends BaseConnectorHandler
        implements InternalConnectorHandler, TriggerProvider, PromptBlockProvider {

    /** Tag of the SYSTEM block carrying the project root of the current IDE session. */
    private static final String IDE_SESSION_BLOCK = "ide_session";

    private final AcpToolService acpToolService;
    private final AcpSessionRegistry sessionRegistry;

    public AcpConnectorService(AcpToolService toolService, AcpSessionRegistry sessionRegistry) {
        super(toolService);
        this.acpToolService = toolService;
        this.sessionRegistry = sessionRegistry;
    }

    /** The fixed tools (read_file/write_file/run_command) plus this IDE session's session-scoped MCP tools. */
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

    /** fs/terminal live in the IDE connection: outside its own ACP run every call fails. */
    @Override
    public boolean sessionScopedTools() {
        return true;
    }

    /** A fixed @Tool → the base's reflection dispatch; otherwise a session MCP tool → a reverse mcp/call_tool. */
    @Override
    public Map<String, Object> executeTool(ConnectorEnv env, String toolName, Map<String, Object> args) {
        if (getTools().containsKey(toolName)) {
            return super.executeTool(env, toolName, args);
        }
        return acpToolService.callMcpTool(env.sessionId(), toolName, args);
    }

    /**
     * Root of the project open in the IDE (the session's ACP {@code cwd}). Without it the agent does
     * not know where the project lives: {@code read_file}/{@code write_file} accept absolute paths
     * only, and there would be nowhere to get them from. The block appears only when the run comes
     * from a live IDE session — for web chat and triggers the {@code sessionId} belongs to something
     * else and is absent from the registry.
     */
    @Override
    public List<PromptBlock> promptBlocks(ConnectorEnv env) {
        UUID sessionId = env.sessionId();
        if (sessionId == null) {
            return List.of();
        }
        String cwd = sessionRegistry.cwd(sessionId);
        if (cwd == null) {
            return List.of();
        }
        return List.of(PromptBlock.system(IDE_SESSION_BLOCK,
                "Project root open in the user's IDE: " + cwd, Map.of()));
    }

    @Override
    public String connectorCode() {
        return AcpChannelHandler.CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "ACP (IDE)";
    }

    @Override
    public String connectorDescription() {
        return "Talk to the agent straight from your IDE over the Agent Client Protocol "
                + "(Zed and other clients): it reads and writes files and runs commands in your project.";
    }

    @Override
    public Map<String, TriggerSpec> getTriggers() {
        return Map.of(AcpChannelHandler.TRIGGER_MESSAGE_RECEIVED, new TriggerSpec(
                "Message from the user typed in the IDE (ACP client)",
                List.of("sessionId", "messageId", "text")));
    }
}
