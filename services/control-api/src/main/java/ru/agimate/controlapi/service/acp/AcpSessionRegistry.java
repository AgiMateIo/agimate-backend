package ru.agimate.controlapi.service.acp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Реестр живых ACP-сессий: связывает {@code channel_sessions.id} с активным клиентским
 * соединением, незавершённым {@code session/prompt}, клиентскими capabilities, корнем проекта
 * ({@code cwd}) и исходящими server→client запросами (fs/terminal-тулы IDE-коннектора).
 *
 * <p>Доставка нотификаций best-effort: нет живого соединения — no-op, сообщение уже лежит в
 * {@code channel_session_messages} и клиент увидит его при {@code session/load}. Состояние
 * in-memory, поэтому live-доставка и IDE-тулы работают при одной реплике control-api (проекция
 * SaveMessage и исполнение тула происходят на инстансе, принявшем соединение/gRPC воркера).
 *
 * <p>Один prompt на сессию: параллельный {@code session/prompt} отклоняется — это требование
 * самого ACP, а single-writer-per-session на очереди {@code agent_exec} дублирует его серверно.
 */
@Slf4j
@Component
public class AcpSessionRegistry {

    /** Живое клиентское соединение; единственная обязанность — отправить готовый JSON-RPC фрейм. */
    public interface Client {
        void send(String frame);
    }

    /** Клиентские capabilities из {@code initialize} — что IDE разрешает вызывать серверу. */
    public record ClientCapabilities(boolean fsRead, boolean fsWrite, boolean terminal) {
        public static final ClientCapabilities NONE = new ClientCapabilities(false, false, false);
    }

    public static final String STOP_END_TURN = "end_turn";
    public static final String STOP_CANCELLED = "cancelled";

    private static final class Attachment {
        final Client client;
        final ClientCapabilities capabilities;
        /** Корень проекта сессии (ACP {@code cwd}); {@code null}, если клиент его не прислал. */
        final String cwd;
        final AtomicReference<Object> pendingRpcId = new AtomicReference<>();

        Attachment(Client client, ClientCapabilities capabilities, String cwd) {
            this.client = client;
            this.capabilities = capabilities;
            this.cwd = cwd;
        }
    }

    /** Исходящий server→client запрос, ждущий ответа клиента (fs/terminal-вызов). */
    private record PendingRequest(Client client, CompletableFuture<JsonNode> future) {}

    /** Ссылка на MCP-тул, проброшенный из IDE: имя сервера и «сырое» имя тула для {@code mcp/call_tool}. */
    public record McpToolRef(String server, String rawName) {}

    /** MCP-тулы одной сессии: спеки (для контекста рана) + ссылки (для маршрутизации вызова), по неймспейс-имени. */
    private record SessionMcpTools(Map<String, ConnectorToolSpec> specs, Map<String, McpToolRef> refs) {}

    private final Map<UUID, Attachment> sessions = new ConcurrentHashMap<>();
    /** id исходящего запроса → ожидание ответа; id уникален глобально (см. {@link #requestCounter}). */
    private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    /** sessionId → MCP-тулы, проброшенные IDE на session/new|load; чистятся на detach. */
    private final Map<UUID, SessionMcpTools> mcpTools = new ConcurrentHashMap<>();
    private final AtomicLong requestCounter = new AtomicLong();

    /**
     * Привязывает сессию к соединению (session/new, session/load); перепривязка допустима.
     * {@code cwd} — корень проекта из того же фрейма: он живёт ровно столько же, сколько
     * соединение (клиент присылает его при каждом new/load), поэтому в БД не хранится.
     */
    public void attach(UUID sessionId, Client client, ClientCapabilities capabilities, String cwd) {
        sessions.put(sessionId, new Attachment(
                client, capabilities == null ? ClientCapabilities.NONE : capabilities, cwd));
    }

    /** Корень проекта сессии (ACP {@code cwd}); {@code null} — сессия не привязана или клиент его не дал. */
    public String cwd(UUID sessionId) {
        Attachment attachment = sessions.get(sessionId);
        return attachment == null ? null : attachment.cwd;
    }

    /** Отвязывает все сессии соединения, чистит их MCP-тулы и завершает висящие запросы ошибкой (разрыв WS). */
    public void detachAll(Client client) {
        sessions.entrySet().removeIf(e -> {
            if (e.getValue().client == client) {
                mcpTools.remove(e.getKey());
                return true;
            }
            return false;
        });
        pendingRequests.forEach((id, pending) -> {
            if (pending.client() == client) {
                pendingRequests.remove(id);
                pending.future().completeExceptionally(new IllegalStateException("IDE disconnected"));
            }
        });
    }

    /** Кладёт MCP-тулы сессии (из {@code _agimateMcp} на session/new|load). Пустой список — чистка. */
    public void putMcpTools(UUID sessionId, Map<String, ConnectorToolSpec> specs, Map<String, McpToolRef> refs) {
        if (specs == null || specs.isEmpty()) {
            mcpTools.remove(sessionId);
            return;
        }
        mcpTools.put(sessionId, new SessionMcpTools(Map.copyOf(specs), Map.copyOf(refs)));
    }

    /** Спеки MCP-тулов сессии для контекста рана (по неймспейс-имени); пусто, если их нет. */
    public Map<String, ConnectorToolSpec> mcpToolSpecs(UUID sessionId) {
        SessionMcpTools tools = mcpTools.get(sessionId);
        return tools == null ? Map.of() : tools.specs();
    }

    /** Ссылка MCP-тула по неймспейс-имени (сервер + сырое имя); {@code null}, если не MCP-тул сессии. */
    public McpToolRef mcpToolRef(UUID sessionId, String name) {
        SessionMcpTools tools = mcpTools.get(sessionId);
        return tools == null ? null : tools.refs().get(name);
    }

    /** Спек MCP-тула по неймспейс-имени — для решения о подтверждении (readOnly); {@code null}, если нет. */
    public ConnectorToolSpec mcpToolSpec(UUID sessionId, String name) {
        SessionMcpTools tools = mcpTools.get(sessionId);
        return tools == null ? null : tools.specs().get(name);
    }

    /** Есть ли живое клиентское соединение для сессии (отличает «нет IDE» от «capability выключена»). */
    public boolean isConnected(UUID sessionId) {
        return sessions.containsKey(sessionId);
    }

    /** Клиентские capabilities активной сессии; {@link ClientCapabilities#NONE} если не привязана. */
    public ClientCapabilities capabilities(UUID sessionId) {
        Attachment attachment = sessions.get(sessionId);
        return attachment == null ? ClientCapabilities.NONE : attachment.capabilities;
    }

    /**
     * Отправляет клиенту JSON-RPC request и возвращает ожидание ответа. Future завершается из
     * {@link #handleResponse} (result/error) или исключением при обрыве соединения.
     *
     * @throws IllegalStateException сессия не привязана к живому соединению
     */
    public CompletableFuture<JsonNode> request(UUID sessionId, String method, Map<String, Object> params) {
        Attachment attachment = sessions.get(sessionId);
        if (attachment == null) {
            throw new IllegalStateException("IDE session is not connected: " + sessionId);
        }
        String id = "srv-" + requestCounter.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(id, new PendingRequest(attachment.client, future));

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("jsonrpc", "2.0");
        frame.put("id", id);
        frame.put("method", method);
        frame.put("params", params);
        try {
            attachment.client.send(JsonUtils.writeValueAsString(frame));
        } catch (Exception e) {
            pendingRequests.remove(id);
            future.completeExceptionally(new IllegalStateException("IDE send failed: " + e.getMessage()));
        }
        return future;
    }

    /** Завершает ожидание по ответу клиента на server→client запрос. Неизвестный id — no-op. */
    public void handleResponse(String id, JsonNode result, JsonNode error) {
        PendingRequest pending = pendingRequests.remove(id);
        if (pending == null) {
            return;
        }
        if (error != null && !error.isNull()) {
            String message = error.path("message").asText("IDE request failed");
            pending.future().completeExceptionally(new IllegalStateException(message));
        } else {
            pending.future().complete(result);
        }
    }

    /** Шлёт нотификацию {@code session/update}; нет живого соединения — no-op (history-only). */
    public void sendUpdate(UUID sessionId, Map<String, Object> update) {
        Attachment attachment = sessions.get(sessionId);
        if (attachment == null) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sessionId", sessionId.toString());
        params.put("update", update);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("jsonrpc", "2.0");
        frame.put("method", "session/update");
        frame.put("params", params);
        sendSafely(sessionId, attachment.client, JsonUtils.writeValueAsString(frame));
    }

    /** Завершает висящий prompt ответом {@code {stopReason}}; без pending — no-op. */
    public boolean completePrompt(UUID sessionId, String stopReason) {
        return finishPrompt(sessionId, (client, rpcId) -> {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("jsonrpc", "2.0");
            frame.put("id", rpcId);
            frame.put("result", Map.of("stopReason", stopReason));
            sendSafely(sessionId, client, JsonUtils.writeValueAsString(frame));
        });
    }

    /** Завершает висящий prompt JSON-RPC ошибкой; без pending — no-op. */
    public boolean failPrompt(UUID sessionId, int code, String message) {
        return finishPrompt(sessionId, (client, rpcId) -> {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("jsonrpc", "2.0");
            frame.put("id", rpcId);
            frame.put("error", Map.of("code", code, "message", message == null ? "agent error" : message));
            sendSafely(sessionId, client, JsonUtils.writeValueAsString(frame));
        });
    }

    /**
     * Регистрирует незавершённый prompt; ответ уйдёт из {@link #completePrompt}/{@link #failPrompt}.
     *
     * @throws IllegalStateException сессия не привязана или prompt уже в полёте
     */
    public void registerPrompt(UUID sessionId, Object rpcId) {
        Attachment attachment = sessions.get(sessionId);
        if (attachment == null) {
            throw new IllegalStateException("Session is not attached to this connection: " + sessionId);
        }
        if (!attachment.pendingRpcId.compareAndSet(null, rpcId)) {
            throw new IllegalStateException("A prompt is already in flight for session: " + sessionId);
        }
    }

    private interface PromptFinisher {
        void finish(Client client, Object rpcId);
    }

    private boolean finishPrompt(UUID sessionId, PromptFinisher finisher) {
        Attachment attachment = sessions.get(sessionId);
        if (attachment == null) {
            return false;
        }
        Object rpcId = attachment.pendingRpcId.getAndSet(null);
        if (rpcId == null) {
            return false;
        }
        finisher.finish(attachment.client, rpcId);
        return true;
    }

    private static void sendSafely(UUID sessionId, Client client, String frame) {
        try {
            client.send(frame);
        } catch (Exception e) {
            log.warn("ACP send failed for session {}: {}", sessionId, e.getMessage());
        }
    }
}
