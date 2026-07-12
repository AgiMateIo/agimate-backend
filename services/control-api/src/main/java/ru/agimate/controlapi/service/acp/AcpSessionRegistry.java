package ru.agimate.controlapi.service.acp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.common.util.JsonUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Реестр живых ACP-сессий: связывает {@code channel_sessions.id} с активным клиентским
 * соединением и незавершённым {@code session/prompt}.
 *
 * <p>Доставка best-effort: нет живого соединения — no-op, сообщение уже лежит в
 * {@code channel_session_messages} и клиент увидит его при {@code session/load}. Состояние
 * in-memory, поэтому live-доставка работает при одной реплике control-api (проекция
 * SaveMessage происходит на инстансе, принявшем gRPC воркера).
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

    public static final String STOP_END_TURN = "end_turn";
    public static final String STOP_CANCELLED = "cancelled";

    private record Attachment(Client client, AtomicReference<Object> pendingRpcId) {}

    private final Map<UUID, Attachment> sessions = new ConcurrentHashMap<>();

    /** Привязывает сессию к соединению (session/new, session/load); перепривязка допустима. */
    public void attach(UUID sessionId, Client client) {
        sessions.put(sessionId, new Attachment(client, new AtomicReference<>()));
    }

    /** Отвязывает все сессии соединения (разрыв WebSocket). Висящие prompt'ы умирают молча. */
    public void detachAll(Client client) {
        sessions.entrySet().removeIf(e -> e.getValue().client() == client);
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
        if (!attachment.pendingRpcId().compareAndSet(null, rpcId)) {
            throw new IllegalStateException("A prompt is already in flight for session: " + sessionId);
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
        sendSafely(sessionId, attachment.client(), JsonUtils.writeValueAsString(frame));
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

    private interface PromptFinisher {
        void finish(Client client, Object rpcId);
    }

    private boolean finishPrompt(UUID sessionId, PromptFinisher finisher) {
        Attachment attachment = sessions.get(sessionId);
        if (attachment == null) {
            return false;
        }
        Object rpcId = attachment.pendingRpcId().getAndSet(null);
        if (rpcId == null) {
            return false;
        }
        finisher.finish(attachment.client(), rpcId);
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
