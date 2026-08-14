package ru.agimate.controlapi.acp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.UnauthorizedStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.config.AcpWebSocketConfig;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.ConnectionToolMapper;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.ChannelSessionMessage;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.security.AgentPrincipal;
import ru.agimate.controlapi.service.trigger.RunCancellationService;
import ru.agimate.controlapi.service.acp.AcpService;
import ru.agimate.controlapi.service.acp.AcpSessionRegistry;
import ru.agimate.controlapi.service.channel.handler.AcpChannelHandler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ACP endpoint (Agent Client Protocol, JSON-RPC 2.0 over WebSocket): a dialogue between an IDE
 * client (Zed and others, through a stdio↔wss bridge) and the agent of the connection's key. It
 * implements the agent-side methods {@code initialize}, {@code session/new}, {@code session/load},
 * {@code session/prompt} and the notification {@code session/cancel}.
 *
 * <p>The answer to {@code session/prompt} is asynchronous: the rpc id is registered in
 * {@link AcpSessionRegistry}, and the answer goes out from {@link AcpChannelHandler} on the ANSWER or
 * ERROR projection of SaveMessage. {@code session/cancel} stops the session's runs (they halt at
 * their next seam) and releases the client with stopReason=cancelled straight away, without waiting
 * for the turn already in progress to unwind.
 *
 * <p>Authentication happens at the handshake ({@link AcpHandshakeInterceptor}), so the ACP method
 * {@code authenticate} is not needed ({@code authMethods: []}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AcpWebSocketHandler extends TextWebSocketHandler {

    public static final int PROTOCOL_VERSION = 1;

    private static final String ATTR_CLIENT = "acpClient";
    private static final String ATTR_CAPABILITIES = "acpCapabilities";
    /** The field in the params of session/new|load where the bridge puts the aggregated list of the IDE's MCP tools. */
    private static final String ATTR_MCP_FIELD = "_agimateMcp";

    private static final int RPC_INVALID_PARAMS = -32602;
    private static final int RPC_METHOD_NOT_FOUND = -32601;
    private static final int RPC_INTERNAL_ERROR = -32603;
    private static final int RPC_NOT_FOUND = -32001;
    private static final int RPC_PROMPT_IN_FLIGHT = -32002;
    private static final int RPC_FORBIDDEN = -32003;

    private final AcpService acpService;
    private final AcpSessionRegistry sessionRegistry;
    private final RunCancellationService runCancellationService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // An outgoing buffer for large frames (the agent's answer, an fs/write with big content) —
        // symmetrical to the container's inbound limit (AcpWebSocketConfig.MAX_MESSAGE_BYTES).
        ConcurrentWebSocketSessionDecorator safeSession = new ConcurrentWebSocketSessionDecorator(
                session, 10_000, AcpWebSocketConfig.MAX_MESSAGE_BYTES);
        AcpSessionRegistry.Client client = frame -> {
            try {
                safeSession.sendMessage(new TextMessage(frame));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        session.getAttributes().put(ATTR_CLIENT, client);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, @NonNull CloseStatus status) {
        sessionRegistry.detachAll(client(session));
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) {
        AcpSessionRegistry.Client client = client(session);
        JsonNode frame = JsonUtils.toJsonNodeOrNull(message.getPayload());
        if (frame == null || !frame.isObject()) {
            client.send(errorFrame(null, -32700, "Parse error"));
            return;
        }

        JsonNode id = frame.get("id");
        String method = frame.path("method").asText(null);
        JsonNode params = frame.path("params");
        if (method == null) {
            // The client's answer to a server→client request (an fs or terminal tool of the IDE connector).
            if (id != null) {
                sessionRegistry.handleResponse(id.asText(), frame.get("result"), frame.get("error"));
            }
            return;
        }

        try {
            switch (method) {
                case "initialize" -> handleInitialize(session, client, id, params);
                case "authenticate" -> client.send(resultFrame(id, Map.of()));
                case "session/new" -> handleSessionNew(session, client, id, params);
                case "session/load" -> handleSessionLoad(session, client, id, params);
                case "session/prompt" -> handleSessionPrompt(session, client, id, params);
                case "session/cancel" -> handleSessionCancel(session, params);
                case "_agimate/restore" -> handleRestore(session, client, params);
                default -> {
                    if (id != null) {
                        client.send(errorFrame(id, RPC_METHOD_NOT_FOUND, "Method not found: " + method));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("ACP method {} failed: {}", method, e.getMessage());
            if (id != null) {
                client.send(errorFrame(id, errorCode(e), e.getMessage()));
            }
        }
    }

    /** Stores the client's capabilities (fs/terminal) in the connection's attributes and answers with the manifest. */
    private void handleInitialize(WebSocketSession session, AcpSessionRegistry.Client client,
                                  JsonNode id, JsonNode params) {
        JsonNode caps = params.path("clientCapabilities");
        AcpSessionRegistry.ClientCapabilities parsed = new AcpSessionRegistry.ClientCapabilities(
                caps.path("fs").path("readTextFile").asBoolean(false),
                caps.path("fs").path("writeTextFile").asBoolean(false),
                caps.path("terminal").asBoolean(false));
        session.getAttributes().put(ATTR_CAPABILITIES, parsed);
        client.send(resultFrame(id, initializeResult()));
    }

    private Map<String, Object> initializeResult() {
        return Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "agentCapabilities", Map.of(
                        "loadSession", true,
                        "promptCapabilities", Map.of(
                                "image", false,
                                "audio", false,
                                "embeddedContext", false)),
                "authMethods", List.of());
    }

    private void handleSessionNew(WebSocketSession session, AcpSessionRegistry.Client client,
                                  JsonNode id, JsonNode params) {
        AgentPrincipal principal = principal(session);
        AgentSession agentSession = acpService.startSession(principal.userId(), principal.agentId());
        UUID sessionId = agentSession.getId();
        sessionRegistry.attach(sessionId, client, capabilities(session), cwd(params));
        storeMcpTools(sessionId, params.path(ATTR_MCP_FIELD));
        client.send(resultFrame(id, Map.of("sessionId", sessionId.toString())));
    }

    /**
     * Recovery after the bridge reconnects (a control-api restart loses the in-memory state while the
     * IDE noticed nothing): the bridge sends a notification with its live sessions and their MCP tools,
     * and the server rebinds each one (with an ownership check) and stores the tools. The capabilities
     * come from the replayed {@code initialize} the bridge sends before restore.
     */
    private void handleRestore(WebSocketSession session, AcpSessionRegistry.Client client, JsonNode params) {
        AgentPrincipal principal = principal(session);
        for (JsonNode s : params.path("sessions")) {
            String raw = s.path("sessionId").asText(null);
            UUID sessionId;
            try {
                sessionId = UUID.fromString(raw);
                acpService.assertOwned(principal.userId(), principal.agentId(), sessionId);
            } catch (Exception e) {
                log.warn("ACP restore skipped for session {}: {}", raw, e.getMessage());
                continue;
            }
            sessionRegistry.attach(sessionId, client, capabilities(session), cwd(s));
            storeMcpTools(sessionId, s.path("mcpTools"));
            log.info("ACP session {} restored after reconnect", sessionId);
        }
    }

    /**
     * Tools of the MCP servers forwarded by the bridge (it started them locally and did a
     * {@code tools/list}): the array {@code [{server, tool}]} → the namespaced name
     * {@code <server>__<tool>} → the spec plus the reference for {@code mcp/call_tool}.
     */
    private void storeMcpTools(UUID sessionId, JsonNode mcp) {
        if (!mcp.isArray() || mcp.isEmpty()) {
            sessionRegistry.putMcpTools(sessionId, Map.of(), Map.of());
            return;
        }
        Map<String, ConnectorToolSpec> specs = new LinkedHashMap<>();
        Map<String, AcpSessionRegistry.McpToolRef> refs = new LinkedHashMap<>();
        for (JsonNode entry : mcp) {
            String server = entry.path("server").asText(null);
            JsonNode tool = entry.path("tool");
            String rawName = tool.path("name").asText(null);
            if (server == null || rawName == null) {
                continue;
            }
            String name = server + "__" + rawName;
            specs.put(name, ConnectionToolMapper.toSpec(name, tool));
            refs.put(name, new AcpSessionRegistry.McpToolRef(server, rawName));
        }
        sessionRegistry.putMcpTools(sessionId, specs, refs);
    }

    /** Replay of the history as session/update notifications (INBOUND/ANSWER; PROGRESS is not replayed), then the answer. */
    private void handleSessionLoad(WebSocketSession session, AcpSessionRegistry.Client client,
                                   JsonNode id, JsonNode params) {
        AgentPrincipal principal = principal(session);
        UUID sessionId = sessionId(params);
        List<ChannelSessionMessage> history =
                acpService.loadSession(principal.userId(), principal.agentId(), sessionId);
        sessionRegistry.attach(sessionId, client, capabilities(session), cwd(params));
        storeMcpTools(sessionId, params.path(ATTR_MCP_FIELD));
        for (ChannelSessionMessage m : history) {
            String updateType = switch (m.getKind()) {
                case INBOUND -> "user_message_chunk";
                case ANSWER -> "agent_message_chunk";
                default -> null;
            };
            if (updateType != null && m.getMessage() != null) {
                sessionRegistry.sendUpdate(sessionId, Map.of(
                        "sessionUpdate", updateType,
                        "content", Map.of("type", "text", "text", m.getMessage())));
            }
        }
        client.send(resultFrame(id, Map.of()));
    }

    private void handleSessionPrompt(WebSocketSession session, AcpSessionRegistry.Client client,
                                     JsonNode id, JsonNode params) {
        AgentPrincipal principal = principal(session);
        UUID sessionId = sessionId(params);
        String text = extractText(params.path("prompt"));

        // Registration before routing: the run's answer must not outrun the pending rpc id.
        sessionRegistry.registerPrompt(sessionId, id);
        try {
            acpService.prompt(principal.userId(), principal.agentId(), sessionId, text);
        } catch (Exception e) {
            log.warn("ACP prompt failed for session {}: {}", sessionId, e.getMessage());
            sessionRegistry.failPrompt(sessionId, errorCode(e), e.getMessage());
        }
    }

    /**
     * Stops the session's runs and releases the client. The release happens even if cancellation throws
     * (a session gone, someone else's): a hanging IDE is worse than a run that keeps going.
     */
    private void handleSessionCancel(WebSocketSession session, JsonNode params) {
        UUID sessionId = sessionId(params);
        try {
            runCancellationService.cancelSession(sessionId, principal(session).userId());
        } catch (Exception e) {
            log.warn("ACP cancel failed for session {}: {}", sessionId, e.getMessage());
        }
        sessionRegistry.completePrompt(sessionId, AcpSessionRegistry.STOP_CANCELLED);
    }

    /** The MVP accepts text blocks only; any other content type is invalid params. */
    private static String extractText(JsonNode prompt) {
        if (!prompt.isArray() || prompt.isEmpty()) {
            throw new BadRequestStatusException("prompt must be a non-empty array of content blocks");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : prompt) {
            if (!"text".equals(block.path("type").asText())) {
                throw new BadRequestStatusException(
                        "Unsupported content block type: " + block.path("type").asText());
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(block.path("text").asText());
        }
        return text.toString();
    }

    /**
     * The session's project root: per ACP the client must send an absolute {@code cwd} in
     * {@code session/new|load} (the bridge duplicates it in {@code _agimate/restore}). A relative or
     * empty one is treated as «not sent»: substituting it into {@code terminal/create} is worse than
     * leaving the decision to the client. A missing root is no reason to tear the session down — the
     * IDE tools work without it too.
     */
    private static String cwd(JsonNode params) {
        String raw = params.path("cwd").asText(null);
        if (raw == null || raw.isBlank() || !raw.startsWith("/")) {
            return null;
        }
        return raw;
    }

    private static UUID sessionId(JsonNode params) {
        String raw = params.path("sessionId").asText(null);
        if (raw == null) {
            throw new BadRequestStatusException("sessionId is required");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new BadRequestStatusException("Invalid sessionId: " + raw);
        }
    }

    private static AgentPrincipal principal(WebSocketSession session) {
        return (AgentPrincipal) session.getAttributes().get(AcpHandshakeInterceptor.ATTR_PRINCIPAL);
    }

    private static AcpSessionRegistry.Client client(WebSocketSession session) {
        return (AcpSessionRegistry.Client) session.getAttributes().get(ATTR_CLIENT);
    }

    private static AcpSessionRegistry.ClientCapabilities capabilities(WebSocketSession session) {
        AcpSessionRegistry.ClientCapabilities caps =
                (AcpSessionRegistry.ClientCapabilities) session.getAttributes().get(ATTR_CAPABILITIES);
        return caps == null ? AcpSessionRegistry.ClientCapabilities.NONE : caps;
    }

    private static int errorCode(Exception e) {
        return switch (e) {
            case BadRequestStatusException ignored -> RPC_INVALID_PARAMS;
            case NotFoundStatusException ignored -> RPC_NOT_FOUND;
            case ForbiddenStatusException ignored -> RPC_FORBIDDEN;
            case UnauthorizedStatusException ignored -> RPC_FORBIDDEN;
            case IllegalStateException ignored -> RPC_PROMPT_IN_FLIGHT;
            default -> RPC_INTERNAL_ERROR;
        };
    }

    private static String resultFrame(JsonNode id, Object result) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("jsonrpc", "2.0");
        frame.put("id", id);
        frame.put("result", result);
        return JsonUtils.writeValueAsString(frame);
    }

    private static String errorFrame(JsonNode id, int code, String message) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("jsonrpc", "2.0");
        frame.put("id", id);
        frame.put("error", Map.of("code", code, "message", message == null ? "error" : message));
        return JsonUtils.writeValueAsString(frame);
    }
}
