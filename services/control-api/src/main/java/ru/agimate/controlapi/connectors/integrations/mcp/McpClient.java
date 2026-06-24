package ru.agimate.controlapi.connectors.integrations.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Тонкий MCP-клиент поверх Streamable HTTP: единственный endpoint, JSON-RPC 2.0 в теле POST,
 * ответ — {@code application/json} (одиночный) либо {@code text/event-stream} (SSE-стрим).
 *
 * <p>Сессия короткоживущая и stateless: каждая высокоуровневая операция ({@link #probe},
 * {@link #listTools}, {@link #callTool}) делает свой {@code initialize} +
 * {@code notifications/initialized}, переиспользуя {@code Mcp-Session-Id} в рамках вызова.
 * Пул долгоживущих сессий — вне scope v1. Внутри слоя бросаем только {@link ConnectorException}.
 */
@Slf4j
@Component
public class McpClient {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String HEADER_SESSION_ID = "Mcp-Session-Id";
    private static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final RestClient restClient;
    private final AtomicLong requestId = new AtomicLong(1);

    public McpClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT);
        factory.setReadTimeout(TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** Конфиг подключения к MCP-серверу из credentials. */
    public record ServerConfig(String url, String authToken, Map<String, String> headers) {}

    /** {@code serverInfo} из ответа на {@code initialize}. */
    public record ServerInfo(String name, String version) {}

    /** Хендшейк: подтверждает доступность/auth, возвращает {@code serverInfo} (используется в validate). */
    public ServerInfo probe(ServerConfig config) {
        return openSession(config).serverInfo();
    }

    /** Полный список тулов сервера (с разворотом пагинации по {@code nextCursor}); raw JSON каждого тула. */
    public List<JsonNode> listTools(ServerConfig config) {
        Session session = openSession(config);
        List<JsonNode> tools = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> params = cursor == null ? Map.of() : Map.of("cursor", cursor);
            JsonNode result = rpc(config, session.sessionId(), "tools/list", params);
            result.path("tools").forEach(tools::add);
            JsonNode next = result.get("nextCursor");
            cursor = next != null && !next.isNull() ? next.asText() : null;
        } while (cursor != null && !cursor.isBlank());
        return tools;
    }

    /** Вызов тула: {@code tools/call}; возвращает {@code result} (content/structuredContent/isError) мапой. */
    public Map<String, Object> callTool(ServerConfig config, String toolName, Map<String, Object> arguments) {
        Session session = openSession(config);
        JsonNode result = rpc(config, session.sessionId(), "tools/call", Map.of(
                "name", toolName,
                "arguments", arguments == null ? Map.of() : arguments));
        return JsonUtils.MAPPER.convertValue(result, JsonUtils.MAP_TYPE_REFERENCE);
    }

    private record Session(String sessionId, ServerInfo serverInfo) {}

    private Session openSession(ServerConfig config) {
        Map<String, Object> params = Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "agimate-control-api", "version", "1.0"));

        long id = requestId.getAndIncrement();
        ResponseEntity<String> response;
        try {
            response = restClient.post()
                    .uri(config.url())
                    .headers(h -> applyHeaders(h, config, null))
                    .body(jsonRpcRequest(id, "initialize", params))
                    .retrieve()
                    .toEntity(String.class);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("MCP initialize failed: " + e.getMessage());
        }

        String sessionId = response.getHeaders().getFirst(HEADER_SESSION_ID);
        JsonNode result = extractResult(response.getBody(), response.getHeaders().getContentType(), id);
        JsonNode info = result.path("serverInfo");
        ServerInfo serverInfo = new ServerInfo(
                info.path("name").asText(""), info.path("version").asText(""));

        sendInitializedNotification(config, sessionId);
        return new Session(sessionId, serverInfo);
    }

    private void sendInitializedNotification(ServerConfig config, String sessionId) {
        try {
            restClient.post()
                    .uri(config.url())
                    .headers(h -> applyHeaders(h, config, sessionId))
                    .body(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // notification — best-effort; часть серверов не требует её для tools/*
            log.debug("MCP notifications/initialized failed (ignored): {}", e.getMessage());
        }
    }

    private JsonNode rpc(ServerConfig config, String sessionId, String method, Map<String, Object> params) {
        long id = requestId.getAndIncrement();
        ResponseEntity<String> response;
        try {
            response = restClient.post()
                    .uri(config.url())
                    .headers(h -> applyHeaders(h, config, sessionId))
                    .body(jsonRpcRequest(id, method, params))
                    .retrieve()
                    .toEntity(String.class);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("MCP " + method + " failed: " + e.getMessage());
        }
        return extractResult(response.getBody(), response.getHeaders().getContentType(), id);
    }

    private void applyHeaders(HttpHeaders headers, ServerConfig config, String sessionId) {
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HEADER_PROTOCOL_VERSION, PROTOCOL_VERSION);
        if (config.authToken() != null && !config.authToken().isBlank()) {
            headers.setBearerAuth(config.authToken());
        }
        if (config.headers() != null) {
            config.headers().forEach(headers::set);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            headers.set(HEADER_SESSION_ID, sessionId);
        }
    }

    private Map<String, Object> jsonRpcRequest(long id, String method, Map<String, Object> params) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);
        return request;
    }

    /**
     * Достаёт {@code result} JSON-RPC-ответа с нашим {@code id}. Тело может быть одиночным JSON
     * ({@code application/json}) или SSE-стримом ({@code text/event-stream}) — во втором случае
     * берём JSON-RPC-сообщение из {@code data:}-строк. JSON-RPC {@code error} → {@link ConnectorException}.
     */
    private JsonNode extractResult(String body, MediaType contentType, long id) {
        if (body == null || body.isBlank()) {
            throw new ConnectorException("Empty MCP response");
        }
        JsonNode message = contentType != null && contentType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                ? parseSseResponse(body, id)
                : JsonUtils.toJsonNode(body);

        if (message == null) {
            throw new ConnectorException("No JSON-RPC response in MCP reply");
        }
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            String msg = error.path("message").asText("unknown error");
            throw new ConnectorException("MCP server error: " + msg);
        }
        return message.path("result");
    }

    private JsonNode parseSseResponse(String body, long id) {
        JsonNode fallback = null;
        for (String line : body.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String data = trimmed.substring("data:".length()).strip();
            if (data.isEmpty()) {
                continue;
            }
            JsonNode node = JsonUtils.toJsonNode(data);
            if (node == null || (!node.has("result") && !node.has("error"))) {
                continue;
            }
            JsonNode nodeId = node.get("id");
            if (nodeId != null && nodeId.asLong(-1) == id) {
                return node;
            }
            fallback = node;
        }
        return fallback;
    }
}
