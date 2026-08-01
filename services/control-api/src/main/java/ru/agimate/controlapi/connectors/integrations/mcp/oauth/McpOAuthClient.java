package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The protocol half of the OAuth flow: building the authorisation URL and talking to the token
 * endpoint. Deliberately stateless and database-free — everything it needs arrives in the arguments,
 * so the orchestration around it (state, statuses, the secret) stays generic.
 *
 * <p>Client identification is a Client ID Metadata Document: {@code client_id} is the HTTPS address
 * of a JSON document we publish, and the authorisation server fetches it to learn our name and our
 * allowed redirect URIs. Nothing is registered and nothing is persisted — one identifier per
 * installation, portable across authorisation servers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpOAuthClient {

    private static final String INVALID_GRANT = "invalid_grant";
    private static final String INVALID_TARGET = "invalid_target";

    private final OAuthHttpClient http;

    /**
     * Address of our client ID metadata document, and with it the client's identity. Taken from
     * configuration and never assembled from the incoming request: behind a reverse proxy {@code Host}
     * is not the public address, the authorisation server compares the value byte for byte with the
     * document's own {@code client_id}, and a mismatch is an {@code invalid_client}.
     */
    @Value("${app.connectors.mcp.oauth.client-id:}")
    private String clientId;

    /** Must be listed in the metadata document's {@code redirect_uris}; the page lives on the front. */
    @Value("${app.connectors.mcp.oauth.redirect-uri:}")
    private String redirectUri;

    public String authorizationUrl(OAuthSetup setup, String state, String codeVerifier) {
        requireConfigured();
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(setup.authorizationEndpoint())
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", state)
                .queryParam("code_challenge", Pkce.challenge(codeVerifier))
                .queryParam("code_challenge_method", Pkce.METHOD);
        if (setup.scope() != null) {
            builder.queryParam("scope", setup.scope());
        }
        if (setup.resource() != null) {
            // MUST regardless of whether the server understands it: the parameter is registered and a
            // conformant server ignores what it does not know.
            builder.queryParam("resource", setup.resource());
        }
        return builder.encode().toUriString();
    }

    public OAuthTokens exchangeCode(OAuthSetup setup, String code, String codeVerifier) {
        requireConfigured();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", clientId);
        form.put("code_verifier", codeVerifier);
        return post(setup, form, "authorization code exchange");
    }

    public OAuthTokens refresh(OAuthSetup setup, String refreshToken) {
        requireConfigured();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", clientId);
        return post(setup, form, "token refresh");
    }

    /**
     * One call, plus at most one retry without the {@code resource} parameter. That retry is a
     * conscious deviation from a MUST, so it is narrow: only on {@code invalid_target}, the error
     * RFC 8707 defines for «I do not understand this resource». On any other failure the first vague
     * 400 would quietly move us into a mode with no audience binding — the one thing that stops a
     * stolen token from working elsewhere.
     */
    private OAuthTokens post(OAuthSetup setup, Map<String, String> form, String operation) {
        Map<String, String> withResource = new LinkedHashMap<>(form);
        if (setup.resource() != null) {
            withResource.put("resource", setup.resource());
        }

        OAuthHttpClient.TokenResponse response = http.postForm(setup.tokenEndpoint(), withResource);
        if (!response.successful()
                && setup.resource() != null
                && response.error().filter(INVALID_TARGET::equals).isPresent()) {
            log.warn("Authorization server {} rejected the resource indicator; retrying {} without it",
                    setup.issuer(), operation);
            response = http.postForm(setup.tokenEndpoint(), form);
        }

        if (response.successful()) {
            OAuthTokens tokens = OAuthTokens.from(response.body(), LocalDateTime.now());
            if (tokens.accessToken() == null) {
                throw new ConnectorException("Authorization server returned no access token");
            }
            return tokens;
        }

        Optional<String> error = response.error();
        if (error.filter(INVALID_GRANT::equals).isPresent()) {
            throw new OAuthGrantRejectedException("Authorization has been revoked or expired");
        }
        // Everything else is transient as far as we can tell: HTTP status plus the OAuth error code,
        // never the server's own text — that would travel verbatim to the agent.
        throw new ConnectorException("Authorization server rejected the " + operation
                + " (HTTP " + response.status() + error.map(code -> ", " + code).orElse("") + ")");
    }

    private void requireConfigured() {
        if (clientId.isBlank() || redirectUri.isBlank()) {
            throw new ConnectorException("OAuth is not configured on this installation "
                    + "(app.connectors.mcp.oauth.client-id / redirect-uri)");
        }
    }
}
