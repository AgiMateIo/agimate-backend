package ru.agimate.controlapi.connectors.internal.acp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
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
 * Тулы IDE-коннектора: исполняются не серверным кодом, а обратным JSON-RPC-вызовом в живое
 * WebSocket-соединение ACP-сессии (клиент — Zed и др.) через {@link AcpSessionRegistry}.
 * Сессия адресуется полем {@link ConnectorEnv#sessionId()}; наличие живого соединения и
 * клиентских capabilities обязательно — иначе тул возвращает {@code ConnectorException}
 * (валидный error tool-result: агент продолжит без IDE, ран не падает).
 *
 * <p>{@code write_file}/{@code run_command} перед действием спрашивают у пользователя
 * подтверждение через ACP {@code session/request_permission} (диалог рисует сам клиент).
 *
 * <p>Бюджеты подобраны под worker poll-timeout ({@code agent.tool.poll-timeout}, дефолт 60s):
 * долгие команды упрутся в него — операторам с IDE-нагруженными агентами поднимать бюджет.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AcpToolService {

    private static final int FS_TIMEOUT_S = 25;
    private static final int PERMISSION_TIMEOUT_S = 30;
    private static final int COMMAND_WAIT_S = 45;
    private static final int TERMINAL_OP_TIMEOUT_S = 10;
    private static final int OUTPUT_BYTE_LIMIT = 64_000;

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
                    + "Requires user confirmation. Long-running commands may time out.",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = true))
    public Map<String, Object> runCommand(
            @ToolParam("Command to execute") String command,
            @ToolParam(value = "Command arguments", required = false) List<String> args,
            @ToolParam(value = "Absolute working directory", required = false) String cwd) {
        UUID sessionId = requireLiveSession();
        requireCapability(caps(sessionId).terminal(), "run commands");
        if (command == null || command.isBlank()) {
            throw new ConnectorException("command is required");
        }
        String title = "Run: " + command + (args == null || args.isEmpty() ? "" : " " + String.join(" ", args));
        requirePermission(sessionId, "run_command", title, "execute");

        Map<String, Object> createParams = new LinkedHashMap<>();
        createParams.put("sessionId", sessionId.toString());
        createParams.put("command", command);
        if (args != null && !args.isEmpty()) {
            createParams.put("args", args);
        }
        if (cwd != null && !cwd.isBlank()) {
            createParams.put("cwd", cwd);
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
            // Таймаут/сбой ожидания: убиваем команду, но всё равно читаем накопленный вывод.
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

    // ===== helpers =====

    /** sessionId рана И живое соединение: разводит «не ACP-ран» / «IDE отключилась» / «capability off». */
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

    /** ACP session/request_permission: allow_once/reject_once; всё кроме явного allow — отказ. */
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

    /** Синхронный вызов клиента: маппит timeout/обрыв/ошибку клиента в {@code ConnectorException}. */
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

    /** Best-effort вызов (kill/release): сбой не должен маскировать основной результат. */
    private void safeCall(UUID sessionId, String method, Map<String, Object> params) {
        try {
            call(sessionId, method, params, TERMINAL_OP_TIMEOUT_S);
        } catch (ConnectorException e) {
            log.warn("ACP {} failed for session {}: {}", method, sessionId, e.getMessage());
        }
    }
}
