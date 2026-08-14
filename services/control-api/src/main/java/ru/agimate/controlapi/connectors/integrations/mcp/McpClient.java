package ru.agimate.controlapi.connectors.integrations.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.AttributionHeaders;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.McpUnauthorizedException;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.WwwAuthenticate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A thin MCP client over Streamable HTTP: a single endpoint, JSON-RPC 2.0 in the POST body, and a
 * response that is either {@code application/json} (a single one) or {@code text/event-stream} (an
 * SSE stream).
 *
 * <p>The session is short-lived and stateless: every high-level operation ({@link #probe},
 * {@link #listTools}, {@link #callTool}) does its own {@code initialize} plus
 * {@code notifications/initialized}, reusing the {@code Mcp-Session-Id} within that call. A pool of
 * long-lived sessions is out of scope for v1. Inside the layer we throw only
 * {@link ConnectorException}.
 */
@Slf4j
@Component
public class McpClient {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String HEADER_SESSION_ID = "Mcp-Session-Id";
    private static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** AgiMate's identity: {@code name} is the machine id, {@code title} the display name (spec 2025-06-18). */
    private static final String CLIENT_NAME = "agimate";
    private static final String CLIENT_TITLE = "AgiMate";

    private final RestClient restClient;
    private final AtomicLong requestId = new AtomicLong(1);

    /** SSRF guard, shared with the OAuth side: every host of the chain is vetted the same way. */
    private final McpTargetGuard targets;

    /** AgiMate brand attribution: the {@code User-Agent} plus the product version for {@code clientInfo.version}. */
    private final AttributionHeaders attribution;

    @Autowired
    public McpClient(McpTargetGuard targets, AttributionHeaders attribution) {
        this.targets = targets;
        this.attribution = attribution;
        // Redirects are never followed: the target is vetted before the request, and a 302 to a
        // private address would step around that check entirely.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(TIMEOUT)
                        .build());
        factory.setReadTimeout(TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** Constructor for tests: no Spring context, and the brand version falls back. */
    McpClient(boolean allowPrivateTargets) {
        this(new McpTargetGuard(allowPrivateTargets), new AttributionHeaders("dev"));
    }

    /** The protocol revision this client speaks; the OAuth side sends it on well-known requests. */
    public static String protocolVersion() {
        return PROTOCOL_VERSION;
    }

    /** Connection config for an MCP server, from the credentials. */
    public record ServerConfig(String url, String authToken, Map<String, String> headers) {}

    /** The {@code serverInfo} from the response to {@code initialize}. */
    public record ServerInfo(String name, String version) {}

    /** Handshake: confirms reachability and auth, and returns {@code serverInfo} (used in validate). */
    public ServerInfo probe(ServerConfig config) {
        return openSession(config).serverInfo();
    }

    /** The server's full tool list (unrolling pagination by {@code nextCursor}); raw JSON for each tool. */
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

    /** Calling a tool: {@code tools/call}; returns the {@code result} (content/structuredContent/isError) as a map. */
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
                        "version", attribution.version()));

        long id = requestId.getAndIncrement();
        ResponseEntity<String> response;
        try {
            response = restClient.post()
                    .uri(config.url())
                    .headers(h -> applyHeaders(h, config, null))
                    .body(jsonRpcRequest(id, "initialize", params))
                    .retrieve()
                    .toEntity(String.class);
        } catch (RestClientResponseException e) {
            throw authorizationFailure(e).orElseGet(
                    () -> new ConnectorException("MCP initialize failed: " + e.getMessage()));
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
            // The notification is best-effort; some servers do not require it for tools/*
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
        } catch (RestClientResponseException e) {
            throw authorizationFailure(e).orElseGet(
                    () -> new ConnectorException("MCP " + method + " failed: " + e.getMessage()));
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
        // The AgiMate brand instead of the default JDK «User-Agent: Java/xx» (anti-branding plus a leak of the
        // JVM version). Set before the user's headers — a power user may override it with their own value.
        headers.set(HttpHeaders.USER_AGENT, attribution.userAgent());
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
     * Extracts the {@code result} of the JSON-RPC response carrying our {@code id}. The body may be a
     * single JSON ({@code application/json}) or an SSE stream ({@code text/event-stream}) — in the
     * latter case we take the JSON-RPC message out of the {@code data:} lines. A JSON-RPC
     * {@code error} → {@link ConnectorException}.
     */
    private JsonNode extractResult(String body, MediaType contentType, long id) {
        if (body == null || body.isBlank()) {
            throw new ConnectorException("Empty MCP response");
        }
        JsonNode message = contentType != null && contentType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                ? parseSseResponse(body, id)
                : JsonUtils.toJsonNodeOrNull(body);

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
     * SSRF guard, see {@link McpTargetGuard}. Applied on every call rather than once at creation: it
     * narrows the DNS-rebinding window, though it does not close it.
     */
    private void validateTarget(String url) {
        targets.requireAllowed(url);
    }

    /**
     * Turns an HTTP failure into an authorisation one — but only 401, and 403 with
     * {@code insufficient_scope}. Everything else stays a plain protocol failure: a 400 from a server
     * that did not understand our revision must not be dressed up as «please log in».
     */
    private static Optional<ConnectorException> authorizationFailure(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status != 401 && status != 403) {
            return Optional.empty();
        }
        WwwAuthenticate challenge = WwwAuthenticate
                .bearer(e.getResponseHeaders() == null
                        ? List.of()
                        : e.getResponseHeaders().getOrEmpty(HttpHeaders.WWW_AUTHENTICATE))
                .orElse(null);
        boolean insufficientScope = challenge != null
                && challenge.parameter("error").filter("insufficient_scope"::equals).isPresent();
        if (status == 403 && !insufficientScope) {
            return Optional.empty();
        }
        return Optional.of(new McpUnauthorizedException(
                insufficientScope
                        ? "MCP server requires additional permissions"
                        : "MCP server requires authorization",
                challenge,
                insufficientScope));
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
            JsonNode node = JsonUtils.toJsonNodeOrNull(data);
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
