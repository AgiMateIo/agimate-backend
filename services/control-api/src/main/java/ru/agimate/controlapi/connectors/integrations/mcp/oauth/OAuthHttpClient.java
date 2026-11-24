package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import ru.agimate.common.net.PublicTargets;
import ru.agimate.common.net.TargetNotAllowedException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.AttributionHeaders;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.integrations.mcp.McpUtils;
import ru.agimate.controlapi.service.http.PublicOnlyHttp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * The only HTTP door of the OAuth flow: metadata documents, client registration and the token
 * endpoint. Separate from {@code McpClient} on purpose — it needs guarantees that client does not
 * (no redirects at all) and none of what that client carries (JSON-RPC, sessions, the protocol
 * version header).
 *
 * <p><strong>Redirects are never followed.</strong> {@link PublicTargets} vets an address before the
 * request, and a {@code 302} to a private address would walk around that check entirely — carrying an
 * authorisation code or a token with it.
 *
 * <p>Every response is read through a ceiling, and the request as a whole has a deadline: the
 * registration endpoint and the token endpoint are remote-controlled metadata, and «drip a byte per
 * read» or «send a gigabyte» must not hold a pooled connection forever.
 */
@Slf4j
@Component
public class OAuthHttpClient {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DEADLINE = Duration.ofSeconds(30);

    /** Metadata and registration: a document, not a payload. */
    private static final int DOCUMENT_LIMIT = 64 * 1024;
    /** Tokens: the response is still small, but bound it independently of the document limit. */
    private static final int TOKEN_LIMIT = 1024 * 1024;

    private final RestClient restClient;
    private final PublicTargets targets;
    private final AttributionHeaders attribution;

    public OAuthHttpClient(PublicOnlyHttp http, AttributionHeaders attribution) {
        this.targets = http.targets();
        this.attribution = attribution;
        this.restClient = http.restClient(READ_TIMEOUT, DEADLINE).build();
    }

    /** https always: down this address travels an authorisation code or a token, not a picture. */
    private void requireAllowed(String url) {
        try {
            targets.requireAllowed(url, true);
        } catch (TargetNotAllowedException e) {
            throw new ConnectorException(e.getMessage());
        }
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
        requireAllowed(url);
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
                        if (!status.is2xxSuccessful()) {
                            return null;
                        }
                        return readBounded(response.getBody(), DOCUMENT_LIMIT, "Metadata");
                    });
            if (body == null || body.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(JsonUtils.toJsonNodeOrNull(body));
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            log.debug("OAuth metadata fetch failed for {}: {}",
                    McpUtils.logTarget(url), McpUtils.loggable(e.getMessage()));
            return Optional.empty();
        }
    }

    public TokenResponse postForm(String url, Map<String, String> form) {
        return postForm(url, form, Map.of());
    }

    /**
     * A token endpoint call. Errors are returned rather than thrown: the caller has to tell
     * {@code invalid_grant} (the grant is dead — re-authorise) from a 5xx (retry later) and from
     * {@code invalid_target} (retry without the resource parameter).
     */
    public TokenResponse postForm(String url, Map<String, String> form, Map<String, String> headers) {
        requireAllowed(url);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        form.forEach(body::add);
        try {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("User-Agent", attribution.userAgent());
            for (Map.Entry<String, String> header : headers.entrySet()) {
                spec.header(header.getKey(), header.getValue());
            }
            return spec.body(body)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.is3xxRedirection()) {
                            throw new ConnectorException("Token endpoint redirects; refusing to follow");
                        }
                        String text = readBounded(response.getBody(), TOKEN_LIMIT, "Token endpoint");
                        JsonNode json = text.isBlank() ? null : JsonUtils.toJsonNodeOrNull(text);
                        return new TokenResponse(status.value(), json);
                    });
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("Token endpoint is unreachable: " + McpUtils.loggable(e.getMessage()));
        }
    }

    /**
     * Client registration. The answer's status is part of the result: a 4xx is a permanent refusal
     * of the metadata, a 5xx is transient, and the registrar is the one that can tell them apart.
     */
    public JsonResponse postJson(String url, JsonNode body) {
        requireAllowed(url);
        try {
            return restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("User-Agent", attribution.userAgent())
                    .body(JsonUtils.writeValueAsString(body))
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.is3xxRedirection()) {
                            throw new ConnectorException("Registration endpoint redirects; refusing to follow");
                        }
                        String text = readBounded(response.getBody(), DOCUMENT_LIMIT, "Registration");
                        JsonNode json = text.isBlank() ? null : JsonUtils.toJsonNodeOrNull(text);
                        return new JsonResponse(status.value(), json);
                    });
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException("Client registration endpoint is unreachable: "
                    + McpUtils.loggable(e.getMessage()));
        }
    }

    static String readBounded(InputStream body, int limit, String what) throws IOException {
        if (body == null) {
            return "";
        }
        try (InputStream stream = body) {
            byte[] data = stream.readNBytes(limit + 1);
            if (data.length > limit) {
                throw new ConnectorException(what + " response is too large; refusing to read it");
            }
            return new String(data, StandardCharsets.UTF_8);
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

    /**
     * @param status HTTP status of the registration endpoint's answer
     * @param body   parsed JSON body; {@code null} when the server sent none or sent non-JSON
     */
    public record JsonResponse(int status, JsonNode body) {

        public Optional<String> error() {
            if (body == null) {
                return Optional.empty();
            }
            String error = body.path("error").asText("");
            return error.isBlank() ? Optional.empty() : Optional.of(error);
        }
    }
}
