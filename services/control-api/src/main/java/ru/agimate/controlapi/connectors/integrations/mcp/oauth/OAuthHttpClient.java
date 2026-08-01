package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.AttributionHeaders;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.integrations.mcp.McpTargetGuard;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * The only HTTP door of the OAuth flow: metadata documents and the token endpoint. Separate from
 * {@code McpClient} on purpose — it needs guarantees that client does not (no redirects at all) and
 * none of what that client carries (JSON-RPC, sessions, the protocol version header).
 *
 * <p><strong>Redirects are never followed.</strong> {@link McpTargetGuard} vets an address before the
 * request, and a {@code 302} to a private address would walk around that check entirely — carrying an
 * authorisation code or a token with it.
 */
@Slf4j
@Component
public class OAuthHttpClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final RestClient restClient;
    private final McpTargetGuard targets;
    private final AttributionHeaders attribution;

    public OAuthHttpClient(McpTargetGuard targets, AttributionHeaders attribution) {
        this.targets = targets;
        this.attribution = attribution;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(TIMEOUT)
                        .build());
        factory.setReadTimeout(TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * A metadata document. {@code empty} when the server answers 4xx — for discovery that is a normal
     * outcome («not here, try the next well-known»), not a failure.
     *
     * @param protocolVersion sent as {@code MCP-Protocol-Version} to the MCP server itself
     *                        (2025-03-26 asked for it); {@code null} for the authorisation server,
     *                        where the header means nothing
     */
    public Optional<JsonNode> getJson(String url, String protocolVersion) {
        targets.requireAllowed(url, true);
        try {
            String body = restClient.get()
                    .uri(url)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("User-Agent", attribution.userAgent())
                    .headers(headers -> {
                        if (protocolVersion != null) {
                            headers.set("MCP-Protocol-Version", protocolVersion);
                        }
                    })
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.is3xxRedirection()) {
                            throw new ConnectorException("Metadata endpoint redirects; refusing to follow");
                        }
                        return status.is2xxSuccessful() ? response.bodyTo(String.class) : null;
                    });
            if (body == null || body.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(JsonUtils.toJsonNode(body));
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            log.debug("OAuth metadata fetch failed for {}: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * A token endpoint call. Errors are returned rather than thrown: the caller has to tell
     * {@code invalid_grant} (the grant is dead — re-authorise) from a 5xx (retry later) and from
     * {@code invalid_target} (retry without the resource parameter).
     */
    public TokenResponse postForm(String url, Map<String, String> form) {
        targets.requireAllowed(url, true);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        form.forEach(body::add);
        try {
            return restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("User-Agent", attribution.userAgent())
                    .body(body)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.is3xxRedirection()) {
                            throw new ConnectorException("Token endpoint redirects; refusing to follow");
                        }
                        String text = response.bodyTo(String.class);
                        JsonNode json = text == null || text.isBlank() ? null : JsonUtils.toJsonNode(text);
                        return new TokenResponse(status.value(), json);
                    });
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("Token endpoint is unreachable: " + e.getMessage());
        }
    }

    /**
     * @param status HTTP status of the token endpoint's answer
     * @param body   parsed JSON body; {@code null} when the server sent none
     */
    public record TokenResponse(int status, JsonNode body) {

        public boolean successful() {
            return status >= 200 && status < 300 && body != null;
        }

        /** The OAuth {@code error} code, or empty when the answer carries none. */
        public Optional<String> error() {
            if (body == null) {
                return Optional.empty();
            }
            String error = body.path("error").asText("");
            return error.isBlank() ? Optional.empty() : Optional.of(error);
        }
    }
}
