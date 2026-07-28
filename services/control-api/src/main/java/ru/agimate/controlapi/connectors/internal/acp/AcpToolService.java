package ru.agimate.controlapi.connectors.internal.acp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.service.acp.AcpSessionRegistry;
import ru.agimate.controlapi.service.acp.AcpSessionRegistry.ClientCapabilities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tools of the IDE connector: executed not by server code but by a reverse JSON-RPC call into the
 * live WebSocket connection of the ACP session (the client being Zed and others), through
 * {@link AcpSessionRegistry}. The session is addressed by {@link ConnectorEnv#sessionId()}; a live
 * connection and the client's capabilities are mandatory — otherwise the tool returns a
 * {@code ConnectorException} (a valid error tool result: the agent carries on without the IDE and the
 * run does not fail).
 *
 * <p>Before acting, {@code write_file}/{@code run_command} ask the user for confirmation through ACP
 * {@code session/request_permission} (the dialogue is drawn by the client itself).
 *
 * <p>The budgets are chosen to fit the worker's poll timeout ({@code agent.tool.poll-timeout},
 * 60s by default): long commands will hit it — operators with IDE-heavy agents should raise the
 * budget.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AcpToolService {

    private static final int FS_TIMEOUT_S = 25;
    private static final int PERMISSION_TIMEOUT_S = 30;
    private static final int COMMAND_WAIT_S = 45;
    private static final int TERMINAL_OP_TIMEOUT_S = 10;
    private static final int MCP_CALL_TIMEOUT_S = 45;
    private static final int OUTPUT_BYTE_LIMIT = 64_000;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AcpSessionRegistry sessionRegistry;

    @Tool(name = "read_file",
            description = "Read a text file open in the user's IDE (including unsaved buffer changes). "
                    + "Absolute path. Optionally start at a 1-based line and limit the number of lines.",
            annotations = @ToolAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = true))
    public Map<String, Object> readFile(
            @ToolParam("Absolute path to the file") String path,
            @ToolParam(value = "1-based line to start from", required = false) Integer line,
            @ToolParam(value = "Maximum number of lines to read", required = false) Integer limit) {
        UUID sessionId = requireLiveSession();
        requireCapability(caps(sessionId).fsRead(), "read files");
        requireAbsolute(path);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sessionId", sessionId.toString());
        params.put("path", path);
        if (line != null) {
            params.put("line", line);
        }
        if (limit != null) {
            params.put("limit", limit);
        }
        JsonNode result = call(sessionId, "fs/read_text_file", params, FS_TIMEOUT_S);
        return Map.of("content", result.path("content").asText(""));
    }

    @Tool(name = "write_file",
            description = "Create or overwrite a text file in the user's IDE. Requires user confirmation. "
                    + "Absolute path; parent directories are created as needed.",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = true))
    public Map<String, Object> writeFile(
            @ToolParam("Absolute path to the file") String path,
            @ToolParam("Full new text content of the file") String content) {
        UUID sessionId = requireLiveSession();
        requireCapability(caps(sessionId).fsWrite(), "write files");
        requireAbsolute(path);
        requirePermission(sessionId, "write_file", "Write file " + path, "edit");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sessionId", sessionId.toString());
        params.put("path", path);
        params.put("content", content == null ? "" : content);
        call(sessionId, "fs/write_text_file", params, FS_TIMEOUT_S);
        return Map.of("ok", true);
    }

    @Tool(name = "run_command",
            description = "Run a shell command in the user's IDE terminal and return its output and exit code. "
                    + "Requires user confirmation. Long-running commands may time out. "
                    + "Runs in the project root unless an absolute working directory is given.",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = true))
    public Map<String, Object> runCommand(
            @ToolParam("Command to execute") String command,
            @ToolParam(value = "Command arguments", required = false) List<String> args,
            @ToolParam(value = "Absolute working directory; defaults to the project root",
                    required = false) String cwd) {
        UUID sessionId = requireLiveSession();
        requireCapability(caps(sessionId).terminal(), "run commands");
        if (command == null || command.isBlank()) {
            throw new ConnectorException("command is required");
        }
        String effectiveCwd = effectiveCwd(sessionId, cwd);
        String title = "Run: " + command + (args == null || args.isEmpty() ? "" : " " + String.join(" ", args));
        requirePermission(sessionId, "run_command", title, "execute");

        Map<String, Object> createParams = new LinkedHashMap<>();
        createParams.put("sessionId", sessionId.toString());
        createParams.put("command", command);
        if (args != null && !args.isEmpty()) {
            createParams.put("args", args);
        }
        if (effectiveCwd != null) {
            createParams.put("cwd", effectiveCwd);
        }
        createParams.put("outputByteLimit", OUTPUT_BYTE_LIMIT);
        String terminalId = call(sessionId, "terminal/create", createParams, TERMINAL_OP_TIMEOUT_S)
                .path("terminalId").asText(null);
        if (terminalId == null) {
            throw new ConnectorException("IDE did not return a terminalId");
        }

        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("sessionId", sessionId.toString());
        ref.put("terminalId", terminalId);
        boolean timedOut = false;
        try {
            call(sessionId, "terminal/wait_for_exit", ref, COMMAND_WAIT_S);
        } catch (ConnectorException e) {
            // A timeout or a failed wait: we kill the command but still read whatever output accumulated.
            timedOut = true;
            safeCall(sessionId, "terminal/kill", ref);
        }
        JsonNode output = call(sessionId, "terminal/output", ref, TERMINAL_OP_TIMEOUT_S);
        safeCall(sessionId, "terminal/release", ref);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("output", output.path("output").asText(""));
        result.put("truncated", output.path("truncated").asBoolean(false));
        JsonNode exit = output.path("exitStatus");
        result.put("exitCode", exit.isMissingNode() || exit.isNull() ? null : exit.path("exitCode").asInt());
        if (timedOut) {
            result.put("timedOut", true);
        }
        return result;
    }

    /**
     * Execution of a session-scoped MCP tool (forwarded from the IDE): an optional confirmation
     * (mutating ones — {@code readOnly=false}), then a reverse {@code mcp/call_tool} into the bridge,
     * which proxies it to Zed's local MCP server. Called from
     * {@code AcpConnectorService.executeTool} with the {@code sessionId} already known (not through
     * {@code ConnectorEnvHolder}).
     */
    public Map<String, Object> callMcpTool(UUID sessionId, String toolName, Map<String, Object> args) {
        if (sessionId == null) {
            throw new ConnectorException("MCP tools are only available inside an active IDE (ACP) session");
        }
        if (!sessionRegistry.isConnected(sessionId)) {
            throw new ConnectorException(
                    "The IDE is not connected right now — its MCP tools are unavailable until it reconnects");
        }
        AcpSessionRegistry.McpToolRef ref = sessionRegistry.mcpToolRef(sessionId, toolName);
        if (ref == null) {
            throw new ConnectorException("Unknown IDE MCP tool: " + toolName);
        }
        if (!isReadOnly(sessionRegistry.mcpToolSpec(sessionId, toolName))) {
            requirePermission(sessionId, toolName, "Call MCP tool " + toolName, "other");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sessionId", sessionId.toString());
        params.put("server", ref.server());
        params.put("name", ref.rawName());
        params.put("arguments", args == null ? Map.of() : args);
        JsonNode result = call(sessionId, "mcp/call_tool", params, MCP_CALL_TIMEOUT_S);
        if (result.isNull() || result.isMissingNode()) {
            return Map.of();
        }
        return JsonUtils.MAPPER.convertValue(result, MAP_TYPE);
    }

    /** By default (no annotations) we treat it as mutating and ask: pessimistic, like the MCP defaults. */
    private static boolean isReadOnly(ConnectorToolSpec spec) {
        return spec != null && spec.annotations() != null && spec.annotations().readOnlyHint();
    }

    // ===== helpers =====

    /** The run's sessionId AND a live connection: this separates «not an ACP run» / «the IDE disconnected» / «capability off». */
    private UUID requireLiveSession() {
        UUID sessionId = requireSession();
        if (!sessionRegistry.isConnected(sessionId)) {
            throw new ConnectorException(
                    "The IDE is not connected right now — IDE tools are unavailable until it reconnects");
        }
        return sessionId;
    }

    private UUID requireSession() {
        ConnectorEnv env = ConnectorEnvHolder.current();
        if (env == null || env.sessionId() == null) {
            throw new ConnectorException("IDE tools are only available inside an active IDE (ACP) session");
        }
        return env.sessionId();
    }

    private ClientCapabilities caps(UUID sessionId) {
        return sessionRegistry.capabilities(sessionId);
    }

    private static void requireCapability(boolean present, String action) {
        if (!present) {
            throw new ConnectorException("The connected IDE does not allow the agent to " + action);
        }
    }

    private static void requireAbsolute(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new ConnectorException("path must be an absolute path");
        }
    }

    /**
     * The command's working directory: either explicitly from the model, or the session's project
     * root (the ACP {@code cwd}). Without the substitution {@code terminal/create} goes out with no
     * {@code cwd} — the spec defines no default, so the client picks its own (for Zed that is the home
     * directory, not the user's project).
     */
    private String effectiveCwd(UUID sessionId, String requested) {
        if (requested != null && !requested.isBlank()) {
            if (!requested.startsWith("/")) {
                throw new ConnectorException("cwd must be an absolute path");
            }
            return requested;
        }
        return sessionRegistry.cwd(sessionId);
    }

    /** ACP session/request_permission: allow_once/reject_once; anything but an explicit allow is a refusal. */
    private void requirePermission(UUID sessionId, String toolName, String title, String kind) {
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("toolCallId", toolName + ":" + UUID.randomUUID());
        toolCall.put("title", title);
        toolCall.put("kind", kind);

        List<Map<String, Object>> options = new ArrayList<>();
        options.add(Map.of("optionId", "allow", "name", "Allow", "kind", "allow_once"));
        options.add(Map.of("optionId", "reject", "name", "Reject", "kind", "reject_once"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sessionId", sessionId.toString());
        params.put("toolCall", toolCall);
        params.put("options", options);

        JsonNode result = call(sessionId, "session/request_permission", params, PERMISSION_TIMEOUT_S);
        JsonNode outcome = result.path("outcome");
        boolean allowed = "selected".equals(outcome.path("outcome").asText())
                && "allow".equals(outcome.path("optionId").asText());
        if (!allowed) {
            throw new ConnectorException("The user declined the '" + toolName + "' request");
        }
    }

    /** Synchronous call into the client: maps a timeout, a disconnect or a client error into a {@code ConnectorException}. */
    private JsonNode call(UUID sessionId, String method, Map<String, Object> params, int timeoutSeconds) {
        CompletableFuture<JsonNode> future;
        try {
            future = sessionRegistry.request(sessionId, method, params);
        } catch (IllegalStateException e) {
            throw new ConnectorException("IDE is not connected: " + e.getMessage());
        }
        try {
            JsonNode result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            return result == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : result;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ConnectorException("IDE did not respond to " + method + " within " + timeoutSeconds + "s");
        } catch (ExecutionException e) {
            String cause = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            throw new ConnectorException("IDE " + method + " failed: " + cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorException("Interrupted while waiting for IDE " + method);
        }
    }

    /** Best-effort call (kill/release): its failure must not mask the main result. */
    private void safeCall(UUID sessionId, String method, Map<String, Object> params) {
        try {
            call(sessionId, method, params, TERMINAL_OP_TIMEOUT_S);
        } catch (ConnectorException e) {
            log.warn("ACP {} failed for session {}: {}", method, sessionId, e.getMessage());
        }
    }
}
