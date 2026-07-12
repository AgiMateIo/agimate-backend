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
import ru.agimate.controlapi.database.entities.ChannelSession;
import ru.agimate.controlapi.database.entities.ChannelSessionMessage;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.security.AgentPrincipal;
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
 * ACP-эндпоинт (Agent Client Protocol, JSON-RPC 2.0 поверх WebSocket): диалог IDE-клиента
 * (Zed и др., через stdio↔wss мост) с агентом ключа соединения. Реализует agent-side методы
 * {@code initialize}, {@code session/new}, {@code session/load}, {@code session/prompt} и
 * нотификацию {@code session/cancel}.
 *
 * <p>Ответ на {@code session/prompt} асинхронный: rpc-id регистрируется в
 * {@link AcpSessionRegistry}, ответ уйдёт из {@link AcpChannelHandler} при ANSWER/ERROR-проекции
 * SaveMessage. {@code session/cancel} мягкий: отпускает клиента со stopReason=cancelled, ран на
 * сервере доработает и его ответ останется в истории сессии.
 *
 * <p>Аутентификация — на handshake ({@link AcpHandshakeInterceptor}), поэтому ACP-метод
 * {@code authenticate} не требуется ({@code authMethods: []}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AcpWebSocketHandler extends TextWebSocketHandler {

    public static final int PROTOCOL_VERSION = 1;

    private static final String ATTR_CLIENT = "acpClient";
    private static final String ATTR_CAPABILITIES = "acpCapabilities";

    private static final int RPC_INVALID_PARAMS = -32602;
    private static final int RPC_METHOD_NOT_FOUND = -32601;
    private static final int RPC_INTERNAL_ERROR = -32603;
    private static final int RPC_NOT_FOUND = -32001;
    private static final int RPC_PROMPT_IN_FLIGHT = -32002;
    private static final int RPC_FORBIDDEN = -32003;

    private final AcpService acpService;
    private final AcpSessionRegistry sessionRegistry;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Буфер исходящих под крупные фреймы (ответ агента, fs/write с большим content) —
        // симметрично входному лимиту контейнера (AcpWebSocketConfig.MAX_MESSAGE_BYTES).
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
        JsonNode frame = JsonUtils.toJsonNode(message.getPayload());
        if (frame == null || !frame.isObject()) {
            client.send(errorFrame(null, -32700, "Parse error"));
            return;
        }

        JsonNode id = frame.get("id");
        String method = frame.path("method").asText(null);
        JsonNode params = frame.path("params");
        if (method == null) {
            // Ответ клиента на server→client запрос (fs/terminal-тул IDE-коннектора).
            if (id != null) {
                sessionRegistry.handleResponse(id.asText(), frame.get("result"), frame.get("error"));
            }
            return;
        }

        try {
            switch (method) {
                case "initialize" -> handleInitialize(session, client, id, params);
                case "authenticate" -> client.send(resultFrame(id, Map.of()));
                case "session/new" -> handleSessionNew(session, client, id);
                case "session/load" -> handleSessionLoad(session, client, id, params);
                case "session/prompt" -> handleSessionPrompt(session, client, id, params);
                case "session/cancel" -> sessionRegistry.completePrompt(
                        sessionId(params), AcpSessionRegistry.STOP_CANCELLED);
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

    /** Сохраняет клиентские capabilities (fs/terminal) в атрибуты соединения и отвечает манифестом. */
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

    private void handleSessionNew(WebSocketSession session, AcpSessionRegistry.Client client, JsonNode id) {
        AgentPrincipal principal = principal(session);
        ChannelSession channelSession = acpService.startSession(principal.userId(), principal.agentId());
        sessionRegistry.attach(channelSession.getId(), client, capabilities(session));
        client.send(resultFrame(id, Map.of("sessionId", channelSession.getId().toString())));
    }

    /** Реплей истории нотификациями session/update (INBOUND/ANSWER; PROGRESS не реплеим), затем ответ. */
    private void handleSessionLoad(WebSocketSession session, AcpSessionRegistry.Client client,
                                   JsonNode id, JsonNode params) {
        AgentPrincipal principal = principal(session);
        UUID sessionId = sessionId(params);
        List<ChannelSessionMessage> history =
                acpService.loadSession(principal.userId(), principal.agentId(), sessionId);
        sessionRegistry.attach(sessionId, client, capabilities(session));
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

        // Регистрация до маршрутизации: ответ рана не должен обогнать pending rpc-id.
        sessionRegistry.registerPrompt(sessionId, id);
        try {
            acpService.prompt(principal.userId(), principal.agentId(), sessionId, text);
        } catch (Exception e) {
            log.warn("ACP prompt failed for session {}: {}", sessionId, e.getMessage());
            sessionRegistry.failPrompt(sessionId, errorCode(e), e.getMessage());
        }
    }

    /** MVP принимает только text-блоки; остальные типы контента — invalid params. */
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
