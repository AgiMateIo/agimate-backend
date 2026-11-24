package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The protocol half of the OAuth flow: building the authorisation URL and talking to the token
 * endpoint. Deliberately stateless and database-free — everything it needs arrives in the arguments,
 * so the orchestration around it (state, statuses, the secret) stays generic.
 *
 * <p>The client identity is an argument, not a field: CIMD takes it from configuration on every call,
 * DCR from the connection's grant or from a registration made for this attempt. This class does not
 * know the difference beyond the auth method it has to perform.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpOAuthClient {

    private static final String INVALID_GRANT = "invalid_grant";
    private static final String INVALID_CLIENT = "invalid_client";
    private static final String INVALID_SCOPE = "invalid_scope";
    private static final String INVALID_TARGET = "invalid_target";

    private final OAuthHttpClient http;
    private final McpOAuthProperties properties;

    public String authorizationUrl(OAuthSetup setup, ClientIdentity identity, String state,
                                   String codeVerifier) {
        requireConfigured(identity);
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(setup.authorizationEndpoint())
                .queryParam("response_type", "code")
                .queryParam("client_id", identity.clientId())
                .queryParam("redirect_uri", properties.getRedirectUri())
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

    public OAuthTokens exchangeCode(OAuthSetup setup, ClientIdentity identity, String code,
                                    String codeVerifier) {
        requireConfigured(identity);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", properties.getRedirectUri());
        form.put("code_verifier", codeVerifier);
        return post(setup, identity, form, "authorization code exchange");
    }

    public OAuthTokens refresh(OAuthSetup setup, ClientIdentity identity, String refreshToken) {
        requireConfigured(identity);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        return post(setup, identity, form, "token refresh");
    }

    /**
     * One call, plus at most one retry without the {@code resource} parameter. That retry is a
     * conscious deviation from a MUST, so it is narrow: only on {@code invalid_target}, the error
     * RFC 8707 defines for «I do not understand this resource». On any other failure the first vague
     * 400 would quietly move us into a mode with no audience binding — the one thing that stops a
     * stolen token from working elsewhere.
     */
    private OAuthTokens post(OAuthSetup setup, ClientIdentity identity, Map<String, String> form,
                             String operation) {
        Map<String, String> withResource = new LinkedHashMap<>(form);
        if (setup.resource() != null) {
            withResource.put("resource", setup.resource());
        }

        OAuthHttpClient.TokenResponse response = request(setup, identity, withResource);
        if (!response.successful()
                && setup.resource() != null
                && response.error().filter(INVALID_TARGET::equals).isPresent()) {
            log.warn("Authorization server {} rejected the resource indicator; retrying {} without it",
                    setup.issuer(), operation);
            response = request(setup, identity, form);
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
        if (error.filter(INVALID_CLIENT::equals).isPresent()
                || (response.status() == 401 && identity.tokenEndpointAuthMethod()
                != TokenEndpointAuthMethod.NONE)) {
            // RFC 6749 §5.2: a client that authenticates with Authorization gets 401 on a bad
            // client, and the body may be empty or not JSON at all. The 401 is the evidence then.
            throw new OAuthClientAuthenticationException(
                    "Authorization server rejected the OAuth client");
        }
        if (error.filter(INVALID_SCOPE::equals).isPresent()) {
            throw new OAuthScopeRejectedException("Authorization server rejected the requested scope");
        }
        // Everything else is transient as far as we can tell: HTTP status plus the OAuth error code,
        // never the server's own text — that would travel verbatim to the agent.
        throw new ConnectorException("Authorization server rejected the " + operation
                + " (HTTP " + response.status() + error.map(code -> ", " + code).orElse("") + ")");
    }

    private OAuthHttpClient.TokenResponse request(OAuthSetup setup, ClientIdentity identity,
                                                  Map<String, String> baseForm) {
        Map<String, String> form = new LinkedHashMap<>(baseForm);
        Map<String, String> headers = new LinkedHashMap<>();
        switch (identity.tokenEndpointAuthMethod()) {
            case CLIENT_SECRET_BASIC -> {
                requireSecret(identity);
                headers.put("Authorization", "Basic " + basic(identity));
            }
            case CLIENT_SECRET_POST -> {
                requireSecret(identity);
                form.put("client_id", identity.clientId());
                form.put("client_secret", identity.clientSecret());
            }
            case NONE -> form.put("client_id", identity.clientId());
        }
        return http.postForm(setup.tokenEndpoint(), form, headers);
    }

    /**
     * Basic credentials are form-urlencoded before base64 (RFC 6749 §2.3.1); the client id is not
     * duplicated in the form. A client secret containing {@code :} or non-ASCII characters is the
     * reason this is not a plain {@code id + ":" + secret}.
     */
    private static String basic(ClientIdentity identity) {
        String user = URLEncoder.encode(identity.clientId(), StandardCharsets.UTF_8);
        String password = URLEncoder.encode(identity.clientSecret(), StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private void requireConfigured(ClientIdentity identity) {
        if (properties.getRedirectUri() == null || properties.getRedirectUri().isBlank()) {
            throw new ConnectorException("OAuth is not configured on this installation "
                    + "(app.connectors.mcp.oauth.redirect-uri)");
        }
        if (identity == null || identity.clientId() == null || identity.clientId().isBlank()) {
            throw new ConnectorException("OAuth client identity is missing for this connection");
        }
    }

    private static void requireSecret(ClientIdentity identity) {
        if (!identity.hasSecret()) {
            throw new ConnectorException("OAuth client secret is missing for this connection");
        }
    }
}
