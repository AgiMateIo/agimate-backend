package ru.agimate.controlapi.connectors.integrations.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
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

    /** Идентификация AgiMate: {@code name} — машинный id, {@code title} — отображаемое имя (spec 2025-06-18). */
    private static final String CLIENT_NAME = "agimate";
    private static final String CLIENT_TITLE = "AgiMate";
    private static final String PRODUCT_URL = "https://agimate.io";
    /** Фолбэк-версия, когда build-info недоступен (запуск из IDE / тесты без Gradle-сборки). */
    private static final String FALLBACK_VERSION = "dev";

    private final RestClient restClient;
    private final AtomicLong requestId = new AtomicLong(1);

    /** SSRF: разрешать ли цели на приватных/loopback-адресах (true — только для локальной разработки). */
    private final boolean allowPrivateTargets;

    /** Версия продукта для {@code clientInfo.version} и {@code User-Agent} (из build-info). */
    private final String clientVersion;
    /** Готовая строка {@code User-Agent}: {@code AgiMate/<version> (+url)}. */
    private final String userAgent;

    @Autowired
    public McpClient(
            @Value("${app.connectors.mcp.allow-private-targets:false}") boolean allowPrivateTargets,
            ObjectProvider<BuildProperties> buildProperties) {
        this(allowPrivateTargets, resolveVersion(buildProperties));
    }

    /** Конструктор для тестов: без Spring-контекста, версия — фолбэк. */
    McpClient(boolean allowPrivateTargets) {
        this(allowPrivateTargets, FALLBACK_VERSION);
    }

    private McpClient(boolean allowPrivateTargets, String clientVersion) {
        this.allowPrivateTargets = allowPrivateTargets;
        this.clientVersion = clientVersion;
        this.userAgent = "AgiMate/" + clientVersion + " (+" + PRODUCT_URL + ")";
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT);
        factory.setReadTimeout(TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    private static String resolveVersion(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties props = buildProperties.getIfAvailable();
        return props != null ? props.getVersion() : FALLBACK_VERSION;
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
        validateTarget(config.url());
        Map<String, Object> params = Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of(
                        "name", CLIENT_NAME,
                        "title", CLIENT_TITLE,
                        "version", clientVersion));

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
        // Бренд AgiMate вместо дефолтного JDK-«User-Agent: Java/xx» (антибрендинг + утечка версии JVM).
        // Ставим до пользовательских headers — power-user может переопределить своим значением.
        headers.set(HttpHeaders.USER_AGENT, userAgent);
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

    /**
     * SSRF-guard: до любого запроса проверяем, что URL ведёт на публичный http(s)-адрес. Резолвим
     * хост и блокируем loopback / link-local (включая {@code 169.254.169.254}) / site-local /
     * any-local / multicast и IPv6 unique-local. Резолв на каждом вызове (а не разово при создании
     * connection) сужает окно DNS-rebinding, но полностью его не закрывает. Флаг
     * {@code app.connectors.mcp.allow-private-targets} снимает проверку для локальной разработки.
     */
    private void validateTarget(String url) {
        if (allowPrivateTargets) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Invalid MCP server URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new ConnectorException("MCP server URL must use http or https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ConnectorException("MCP server URL has no host");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new ConnectorException("Cannot resolve MCP server host: " + host);
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new ConnectorException(
                        "MCP server URL resolves to a non-public address and is not allowed");
            }
        }
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        // IPv6 unique-local (fc00::/7) — InetAddress.isSiteLocalAddress его не покрывает.
        return address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc;
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
