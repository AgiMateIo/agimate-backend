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
 * Registry of live ACP sessions: it ties a {@code channel_sessions.id} to the active client
 * connection, the pending {@code session/prompt}, the client's capabilities, the project root
 * ({@code cwd}) and the outgoing server→client requests (the IDE connector's fs/terminal tools).
 *
 * <p>Notification delivery is best-effort: with no live connection it is a no-op, the message already
 * sits in {@code channel_session_messages} and the client will see it on {@code session/load}. The
 * state is in memory, so live delivery and the IDE tools work with a single control-api replica (the
 * SaveMessage projection and the tool's execution happen on the instance that accepted the connection
 * and the worker's gRPC).
 *
 * <p>One prompt per session: a concurrent {@code session/prompt} is rejected — that is ACP's own
 * requirement, and single-writer-per-session on the {@code agent_exec} queue duplicates it
 * server-side.
 */
@Slf4j
@Component
public class AcpSessionRegistry {

    /** A live client connection; its only duty is to send a ready JSON-RPC frame. */
    public interface Client {
        void send(String frame);
    }

    /** The client's capabilities from {@code initialize} — what the IDE lets the server call. */
    public record ClientCapabilities(boolean fsRead, boolean fsWrite, boolean terminal) {
        public static final ClientCapabilities NONE = new ClientCapabilities(false, false, false);
    }

    public static final String STOP_END_TURN = "end_turn";
    public static final String STOP_CANCELLED = "cancelled";

    private static final class Attachment {
        final Client client;
        final ClientCapabilities capabilities;
        /** The session's project root (the ACP {@code cwd}); {@code null} when the client did not send it. */
        final String cwd;
        final AtomicReference<Object> pendingRpcId = new AtomicReference<>();

        Attachment(Client client, ClientCapabilities capabilities, String cwd) {
            this.client = client;
            this.capabilities = capabilities;
            this.cwd = cwd;
        }
    }

    /** An outgoing server→client request awaiting the client's answer (an fs or terminal call). */
    private record PendingRequest(Client client, CompletableFuture<JsonNode> future) {}

    /** A reference to an MCP tool forwarded from the IDE: the server's name and the tool's raw name for {@code mcp/call_tool}. */
    public record McpToolRef(String server, String rawName) {}

    /** MCP tools of one session: the specs (for the run's context) plus the references (for routing a call), by namespaced name. */
    private record SessionMcpTools(Map<String, ConnectorToolSpec> specs, Map<String, McpToolRef> refs) {}

    private final Map<UUID, Attachment> sessions = new ConcurrentHashMap<>();
    /** Outgoing request id → the pending wait; the id is globally unique (see {@link #requestCounter}). */
    private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    /** sessionId → the MCP tools forwarded by the IDE on session/new|load; cleared on detach. */
    private final Map<UUID, SessionMcpTools> mcpTools = new ConcurrentHashMap<>();
    private final AtomicLong requestCounter = new AtomicLong();

    /**
     * Binds a session to a connection (session/new, session/load); rebinding is allowed. {@code cwd}
     * is the project root from that same frame: it lives exactly as long as the connection (the client
     * sends it on every new/load), so it is not stored in the database.
     */
    public void attach(UUID sessionId, Client client, ClientCapabilities capabilities, String cwd) {
        sessions.put(sessionId, new Attachment(
                client, capabilities == null ? ClientCapabilities.NONE : capabilities, cwd));
    }

    /** The session's project root (the ACP {@code cwd}); {@code null} — the session is unbound or the client gave none. */
    public String cwd(UUID sessionId) {
        Attachment attachment = sessions.get(sessionId);
        return attachment == null ? null : attachment.cwd;
    }

    /** Unbinds every session of a connection, clears their MCP tools and fails the pending requests (the WS dropped). */
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

    /** Stores a session's MCP tools (from {@code _agimateMcp} on session/new|load). An empty list clears them. */
    public void putMcpTools(UUID sessionId, Map<String, ConnectorToolSpec> specs, Map<String, McpToolRef> refs) {
        if (specs == null || specs.isEmpty()) {
            mcpTools.remove(sessionId);
            return;
        }
        mcpTools.put(sessionId, new SessionMcpTools(Map.copyOf(specs), Map.copyOf(refs)));
    }

    /** Specs of a session's MCP tools for the run's context (by namespaced name); empty when there are none. */
    public Map<String, ConnectorToolSpec> mcpToolSpecs(UUID sessionId) {
        SessionMcpTools tools = mcpTools.get(sessionId);
        return tools == null ? Map.of() : tools.specs();
    }

    /** Reference to an MCP tool by its namespaced name (server plus raw name); {@code null} when it is not one of the session's MCP tools. */
    public McpToolRef mcpToolRef(UUID sessionId, String name) {
        SessionMcpTools tools = mcpTools.get(sessionId);
        return tools == null ? null : tools.refs().get(name);
    }

    /** Spec of an MCP tool by its namespaced name — used to decide about confirmation (readOnly); {@code null} when absent. */
    public ConnectorToolSpec mcpToolSpec(UUID sessionId, String name) {
        SessionMcpTools tools = mcpTools.get(sessionId);
        return tools == null ? null : tools.specs().get(name);
    }

    /** Whether the session has a live client connection (this separates «no IDE» from «the capability is off»). */
    public boolean isConnected(UUID sessionId) {
        return sessions.containsKey(sessionId);
    }

    /** Client capabilities of the active session; {@link ClientCapabilities#NONE} when unbound. */
    public ClientCapabilities capabilities(UUID sessionId) {
        Attachment attachment = sessions.get(sessionId);
        return attachment == null ? ClientCapabilities.NONE : attachment.capabilities;
    }

    /**
     * Sends a JSON-RPC request to the client and returns the pending wait. The future completes from
     * {@link #handleResponse} (result/error) or with an exception when the connection drops.
     *
     * @throws IllegalStateException the session is not bound to a live connection
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

    /** Completes a wait with the client's answer to a server→client request. An unknown id is a no-op. */
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

    /** Sends a {@code session/update} notification; with no live connection it is a no-op (history-only). */
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

    /** Completes a pending prompt with {@code {stopReason}}; with nothing pending it is a no-op. */
    public boolean completePrompt(UUID sessionId, String stopReason) {
        return finishPrompt(sessionId, (client, rpcId) -> {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("jsonrpc", "2.0");
            frame.put("id", rpcId);
            frame.put("result", Map.of("stopReason", stopReason));
            sendSafely(sessionId, client, JsonUtils.writeValueAsString(frame));
        });
    }

    /** Completes a pending prompt with a JSON-RPC error; with nothing pending it is a no-op. */
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
     * Registers a pending prompt; the answer will go out from {@link #completePrompt}/{@link #failPrompt}.
     *
     * @throws IllegalStateException the session is unbound or a prompt is already in flight
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
