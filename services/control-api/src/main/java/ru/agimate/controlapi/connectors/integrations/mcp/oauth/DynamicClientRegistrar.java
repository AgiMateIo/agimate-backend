package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.integrations.mcp.McpUtils;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * RFC 7591 registration: one HTTP call, no state. What the answer contains is treated as a fact, not
 * as the RFC's ideal — real servers return only {@code client_id} and {@code client_secret}, and a
 * parser that insists on echo fields would reject the servers this fallback exists for.
 *
 * <p>Registration is not management: RFC 7592 is out of scope, so the registration access token and
 * client URI are ignored. Their absence is the normal case.
 *
 * <p>The endpoint comes from remote AS metadata; {@link OAuthHttpClient} is the gatekeeper — it
 * requires https, vets the host through {@code PublicTargets} (no loopback/private/link-local
 * addresses) and does not follow redirects. There is no second HTTP path around that check.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicClientRegistrar {

    private static final String CLIENT_NAME = "AgiMate";
    private static final String BASIC = "client_secret_basic";
    private static final String POST = "client_secret_post";
    private static final String NONE = "none";

    private final OAuthHttpClient http;
    private final McpOAuthProperties properties;

    public RegisteredClient register(OAuthSetup setup) {
        if (!properties.isDcrEnabled()) {
            throw new ConnectorException("Dynamic client registration is disabled on this installation "
                    + "(app.connectors.mcp.oauth.dcr-enabled)");
        }
        if (setup.client().source() != OAuthClientSource.DCR) {
            throw new ConnectorException("This authorization server was discovered for CIMD, not DCR");
        }
        String endpoint = setup.client().registrationEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new ConnectorException("Authorization server " + setup.issuer()
                    + " did not advertise a registration endpoint");
        }
        String redirectUri = properties.getRedirectUri();
        if (redirectUri == null || redirectUri.isBlank()) {
            throw new ConnectorException("OAuth is not configured on this installation "
                    + "(app.connectors.mcp.oauth.redirect-uri)");
        }

        String applicationType = applicationType(redirectUri);
        TokenEndpointAuthMethod method = selectMethod(applicationType,
                setup.client().tokenEndpointAuthMethods());
        String scope = OAuthCredentials.registrationScope(setup.scope());

        ObjectNode request = JsonUtils.MAPPER.createObjectNode();
        request.put("application_type", applicationType);
        request.put("client_name", CLIENT_NAME);
        request.putArray("redirect_uris").add(redirectUri);
        request.putArray("grant_types").add("authorization_code").add("refresh_token");
        request.putArray("response_types").add("code");
        request.put("token_endpoint_auth_method", method.wireName());
        if (scope != null) {
            request.put("scope", scope);
        }

        OAuthHttpClient.JsonResponse response = http.postJson(endpoint, request);
        if (response.status() < 200 || response.status() >= 300) {
            throw failure(setup, response);
        }
        RegisteredClient registered = parse(setup, redirectUri, scope, method, response.body());
        log.info("Registered a DCR client at {} for authorization server {}",
                McpUtils.logTarget(endpoint), setup.issuer());
        return registered;
    }

    /**
     * {@code web} for an https redirect, {@code native} for an http loopback. Anything else is
     * neither, and guessing one would make the authorisation server reject the registration or the
     * redirect later.
     */
    private static String applicationType(String redirectUri) {
        URI uri = parseUri(redirectUri);
        String scheme = lower(uri.getScheme());
        if ("https".equals(scheme)) {
            return "web";
        }
        if ("http".equals(scheme) && isLoopback(uri.getHost())) {
            return "native";
        }
        throw new ConnectorException("OAuth redirect-uri must be https or an http loopback address");
    }

    private static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized) || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized) || "[::1]".equals(normalized);
    }

    /**
     * The client we can actually be. A server-side application prefers a secret; a native client is
     * public by RFC 8252 and authenticates as {@code none}. An empty advertisement means the RFC 8414
     * default, {@code client_secret_basic}.
     */
    private static TokenEndpointAuthMethod selectMethod(String applicationType, List<String> advertised) {
        List<String> methods = advertised == null || advertised.isEmpty() ? List.of(BASIC) : advertised;
        if ("native".equals(applicationType)) {
            if (methods.contains(NONE)) {
                return TokenEndpointAuthMethod.NONE;
            }
            throw new ConnectorException("Authorization server does not support public clients "
                    + "(token_endpoint_auth_method none), which a native application requires");
        }
        if (methods.contains(BASIC)) {
            return TokenEndpointAuthMethod.CLIENT_SECRET_BASIC;
        }
        if (methods.contains(POST)) {
            return TokenEndpointAuthMethod.CLIENT_SECRET_POST;
        }
        if (methods.contains(NONE)) {
            return TokenEndpointAuthMethod.NONE;
        }
        throw new ConnectorException("Authorization server advertises no supported token endpoint "
                + "authentication method");
    }

    private static ConnectorException failure(OAuthSetup setup, OAuthHttpClient.JsonResponse response) {
        Optional<String> error = response.error();
        String code = error.map(value -> ", " + value).orElse("");
        if (response.status() >= 500) {
            return new ConnectorException("Client registration at " + setup.issuer()
                    + " failed (HTTP " + response.status() + code + "); try again");
        }
        return new ConnectorException("Client registration at " + setup.issuer()
                + " was refused (HTTP " + response.status() + code + ")");
    }

    private static RegisteredClient parse(OAuthSetup setup, String redirectUri, String scope,
                                          TokenEndpointAuthMethod requested, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ConnectorException("Client registration at " + setup.issuer()
                    + " returned no JSON object");
        }
        String clientId = text(body, "client_id");
        if (clientId == null) {
            throw new ConnectorException("Client registration at " + setup.issuer()
                    + " returned no client_id");
        }
        String secret = text(body, "client_secret");
        String declared = text(body, "token_endpoint_auth_method");
        TokenEndpointAuthMethod method = requested;
        if (declared != null) {
            method = TokenEndpointAuthMethod.from(declared);
            if (method == null) {
                throw new ConnectorException("Client registration at " + setup.issuer()
                        + " declared an unsupported token endpoint authentication method");
            }
        }
        if (secret == null && method != TokenEndpointAuthMethod.NONE) {
            throw new ConnectorException("Client registration at " + setup.issuer()
                    + " returned no client_secret for a confidential client");
        }
        if (secret != null && method == TokenEndpointAuthMethod.NONE && declared != null) {
            // The server answered a public-client method while issuing a secret: it did not honour
            // the request. A secret returned for a request of `none` without any declared method is
            // tolerated and simply unused; some servers issue one anyway.
            throw new ConnectorException("Client registration at " + setup.issuer()
                    + " declared none while returning a client_secret");
        }
        requireRedirect(body, setup.issuer(), redirectUri);

        long expiresAt = 0;
        JsonNode expiry = body.get("client_secret_expires_at");
        if (expiry != null && expiry.canConvertToLong()) {
            expiresAt = expiry.asLong();
        }
        // The server may normalise or narrow the scope; its answer is the fact the reuse check needs.
        String registeredScope = text(body, "scope");
        return new RegisteredClient(
                new ClientIdentity(OAuthClientSource.DCR, clientId, secret, method),
                registeredScope != null ? registeredScope : scope,
                expiresAt > 0 ? expiresAt : null);
    }

    /**
     * A server that echoes {@code redirect_uris} may have normalised them (host case, default port,
     * trailing slash). Comparing raw strings would reject a registration that is in fact ours.
     */
    private static void requireRedirect(JsonNode body, String issuer, String redirectUri) {
        JsonNode declared = body.get("redirect_uris");
        if (declared == null || !declared.isArray() || declared.isEmpty()) {
            return;
        }
        String expected = normalizeRedirect(redirectUri);
        for (JsonNode item : declared) {
            String value = item.asText("");
            if (!value.isBlank() && expected.equals(normalizeRedirect(value))) {
                return;
            }
        }
        throw new ConnectorException("Client registration at " + issuer
                + " lists a different redirect_uri");
    }

    private static String normalizeRedirect(String value) {
        URI uri = parseUri(value);
        String scheme = lower(uri.getScheme());
        String host = lower(uri.getHost());
        if (scheme == null || host == null) {
            throw new ConnectorException("OAuth redirect-uri is not an absolute http(s) URL");
        }
        int port = uri.getPort();
        if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) {
            port = -1;
        }
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        return scheme + "://" + host + (port > 0 ? ":" + port : "") + path;
    }

    private static URI parseUri(String value) {
        try {
            return URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("OAuth redirect-uri is not a valid URL");
        }
    }

    private static String text(JsonNode body, String field) {
        String value = body.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
