package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finding out where a server's authorisation server is, and whether we can speak to it at all.
 *
 * <p>The question is «did we find an authorisation server», not «which revision does the server
 * speak»: the protocol version arrives in the answer to {@code initialize}, and on a protected server
 * we get a 401 before any answer at all. Three paths, in order, first hit wins — and a refusal only
 * when all three miss.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpAuthDiscovery {

    private static final String PRM_WELL_KNOWN = "/.well-known/oauth-protected-resource";
    private static final String AS_WELL_KNOWN = "/.well-known/oauth-authorization-server";
    private static final String OIDC_WELL_KNOWN = "/.well-known/openid-configuration";
    private static final String OFFLINE_ACCESS = "offline_access";

    private final OAuthHttpClient http;

    /**
     * @param challenge the {@code Bearer} challenge from the 401, when the server sent one — it may
     *                  carry both the metadata location and the scope required for the operation
     * @return empty when no authorisation server could be found; then OAuth is simply not available
     *         for this server and the user needs a static token
     */
    public Optional<OAuthSetup> discover(String serverUrl, WwwAuthenticate challenge, String protocolVersion) {
        Optional<JsonNode> resourceMetadata = fetchResourceMetadata(serverUrl, challenge, protocolVersion);

        String issuer;
        String resource;
        List<String> resourceScopes;
        boolean legacy = resourceMetadata.isEmpty();

        if (resourceMetadata.isPresent()) {
            JsonNode prm = resourceMetadata.get();
            resource = canonicalResource(prm, serverUrl);
            issuer = firstAuthorizationServer(prm);
            resourceScopes = stringList(prm.get("scopes_supported"));
            if (issuer == null) {
                log.info("Protected resource metadata of {} lists no authorization_servers", serverUrl);
                return Optional.empty();
            }
        } else {
            // Legacy 2025-03-26: the MCP server is its own authorisation server and publishes the
            // metadata at its origin's root. Modern vendors still live this way (Atlassian), and
            // without this path they cannot be connected at all — they have no static token either.
            // The origin is the only source accepted here: same operator, same trust as PRM.
            issuer = origin(serverUrl);
            resource = canonicalUrl(serverUrl);
            resourceScopes = List.of();
        }

        Optional<JsonNode> document = fetchAuthorizationServerMetadata(issuer);
        if (document.isEmpty() && legacy) {
            // No protected resource metadata and no legacy metadata either: this server simply does
            // not tell us where to authorise. Not a failure of ours, and not a broken server.
            return Optional.empty();
        }
        JsonNode metadata = document.orElseThrow(() -> new ConnectorException(
                "Authorization server metadata not found for " + issuer));

        requirePkce(metadata, issuer);
        requireClientIdMetadataDocuments(metadata, issuer);

        String scope = chooseScope(challenge, resourceScopes, metadata);
        return Optional.of(new OAuthSetup(
                issuer,
                required(metadata, "authorization_endpoint", issuer),
                required(metadata, "token_endpoint", issuer),
                resource,
                scope));
    }

    /** Header first, then the two well-known URIs the spec prescribes, in that order. */
    private Optional<JsonNode> fetchResourceMetadata(String serverUrl, WwwAuthenticate challenge,
                                                     String protocolVersion) {
        Optional<String> advertised = challenge == null
                ? Optional.empty()
                : challenge.parameter("resource_metadata");
        if (advertised.isPresent()) {
            Optional<JsonNode> document = http.getJson(advertised.get(), protocolVersion);
            if (document.isPresent()) {
                return document;
            }
            log.debug("resource_metadata {} advertised but not readable; falling back to well-known",
                    advertised.get());
        }
        for (String url : resourceMetadataCandidates(serverUrl)) {
            Optional<JsonNode> document = http.getJson(url, protocolVersion);
            if (document.isPresent()) {
                return document;
            }
        }
        return Optional.empty();
    }

    private static List<String> resourceMetadataCandidates(String serverUrl) {
        List<String> candidates = new ArrayList<>();
        String origin = origin(serverUrl);
        String path = path(serverUrl);
        if (!path.isEmpty()) {
            candidates.add(origin + PRM_WELL_KNOWN + path);
        }
        candidates.add(origin + PRM_WELL_KNOWN);
        return candidates;
    }

    /**
     * RFC 8414 with path insertion, then OpenID Connect Discovery both ways — the order the spec
     * prescribes. The document is accepted only when its {@code issuer} is identical to the
     * identifier the URL was built from: a document served from an attacker's host that claims an
     * honest issuer is exactly what this check exists for.
     */
    private Optional<JsonNode> fetchAuthorizationServerMetadata(String issuer) {
        for (String url : authorizationServerCandidates(issuer)) {
            Optional<JsonNode> document = http.getJson(url, null);
            if (document.isEmpty()) {
                continue;
            }
            String declared = document.get().path("issuer").asText("");
            if (!issuer.equals(declared)) {
                log.warn("Authorization server metadata at {} declares issuer {} — rejected", url, declared);
                continue;
            }
            return document;
        }
        return Optional.empty();
    }

    private static List<String> authorizationServerCandidates(String issuer) {
        String origin = origin(issuer);
        String path = path(issuer);
        if (path.isEmpty()) {
            return List.of(origin + AS_WELL_KNOWN, origin + OIDC_WELL_KNOWN);
        }
        return List.of(
                origin + AS_WELL_KNOWN + path,
                origin + OIDC_WELL_KNOWN + path,
                origin + path + OIDC_WELL_KNOWN);
    }

    /**
     * PKCE support is not discoverable any other way, so the metadata is the only evidence — and its
     * absence means «refuse», not «try anyway». The list is checked for {@code S256} rather than for
     * being non-empty: a server offering only {@code plain} is a reason to stop, not to downgrade.
     */
    private static void requirePkce(JsonNode metadata, String issuer) {
        List<String> methods = stringList(metadata.get("code_challenge_methods_supported"));
        if (methods.isEmpty()) {
            throw new ConnectorException(
                    "Authorization server " + issuer + " does not advertise PKCE support");
        }
        if (!methods.contains(Pkce.METHOD)) {
            throw new ConnectorException(
                    "Authorization server " + issuer + " does not support the S256 PKCE method");
        }
    }

    /**
     * The only client identification v1 has. The refusal is logged with the issuer on purpose: that
     * log is the measurement by which the manual fallback gets its priority back.
     */
    private static void requireClientIdMetadataDocuments(JsonNode metadata, String issuer) {
        if (!metadata.path("client_id_metadata_document_supported").asBoolean(false)) {
            log.info("Authorization server {} does not support client ID metadata documents", issuer);
            throw new ConnectorException("Authorization server " + issuer
                    + " does not support client ID metadata documents; this server is not supported yet");
        }
    }

    /**
     * The spec's strategy: the challenge is authoritative, then the resource's own
     * {@code scopes_supported}, then no scope parameter at all. {@code offline_access} is a separate
     * question with a separate source — the authorisation server's metadata, not the resource's.
     */
    private static String chooseScope(WwwAuthenticate challenge, List<String> resourceScopes, JsonNode metadata) {
        Set<String> scopes = new LinkedHashSet<>();
        Optional<String> challenged = challenge == null ? Optional.empty() : challenge.parameter("scope");
        if (challenged.isPresent()) {
            scopes.addAll(List.of(challenged.get().trim().split("\\s+")));
        } else {
            scopes.addAll(resourceScopes);
        }
        if (stringList(metadata.get("scopes_supported")).contains(OFFLINE_ACCESS)) {
            scopes.add(OFFLINE_ACCESS);
        }
        return scopes.isEmpty() ? null : String.join(" ", scopes);
    }

    /**
     * The resource identifier comes from the metadata, not from what the user typed — the two often
     * differ, and a token minted for the wrong audience is rejected by a server that checks it. The
     * document is trusted only when it describes the server it was fetched from.
     */
    private static String canonicalResource(JsonNode prm, String serverUrl) {
        String declared = prm.path("resource").asText("");
        if (declared.isBlank()) {
            return canonicalUrl(serverUrl);
        }
        if (!origin(declared).equalsIgnoreCase(origin(serverUrl))) {
            throw new ConnectorException(
                    "Protected resource metadata describes a different server: " + declared);
        }
        return canonicalUrl(declared);
    }

    private static String firstAuthorizationServer(JsonNode prm) {
        List<String> servers = stringList(prm.get("authorization_servers"));
        return servers.isEmpty() ? null : servers.getFirst();
    }

    private static String required(JsonNode metadata, String field, String issuer) {
        String value = metadata.path(field).asText("");
        if (value.isBlank()) {
            throw new ConnectorException(
                    "Authorization server " + issuer + " metadata has no " + field);
        }
        return value;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(node.size());
        node.forEach(item -> {
            String text = item.asText("");
            if (!text.isBlank()) {
                values.add(text);
            }
        });
        return values;
    }

    /** No fragment, no trailing slash — the form RFC 8707 and the spec ask for. */
    private static String canonicalUrl(String url) {
        String value = url.trim();
        int fragment = value.indexOf('#');
        if (fragment >= 0) {
            value = value.substring(0, fragment);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String origin(String url) {
        URI uri = uri(url);
        String origin = uri.getScheme() + "://" + uri.getHost();
        return uri.getPort() > 0 ? origin + ":" + uri.getPort() : origin;
    }

    private static String path(String url) {
        String path = uri(url).getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "";
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static URI uri(String url) {
        try {
            return URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Invalid URL: " + url);
        }
    }
}
