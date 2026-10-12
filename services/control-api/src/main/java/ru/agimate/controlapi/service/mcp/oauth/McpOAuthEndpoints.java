package ru.agimate.controlapi.service.mcp.oauth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.McpServerProperties;
import ru.agimate.controlapi.controller.mcp.McpController;

import java.net.URI;
import java.util.Locale;

/**
 * Every address of the MCP server's authorization server, derived once from configuration. The
 * consent page is the one address outside control-api: it has no browser session, so the page lives
 * on the front and calls {@code /manage} with the user's token.
 */
@Component
public class McpOAuthEndpoints {

    public static final String CONSENT_PATH = "/mcp/authorize";
    public static final String TOKEN_PATH = "/oauth/token";
    public static final String REVOKE_PATH = "/oauth/revoke";
    public static final String PROTECTED_RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource";
    public static final String AUTHORIZATION_SERVER_METADATA_PATH = "/.well-known/oauth-authorization-server";
    public static final String OPENID_CONFIGURATION_PATH = "/.well-known/openid-configuration";

    private final String issuer;
    private final String resource;
    private final String canonicalResource;
    private final String issuerPath;
    private final String resourcePath;
    private final String consentUrl;

    public McpOAuthEndpoints(McpServerProperties properties, @Value("${app.frontend.base-url}") String frontendBaseUrl) {
        this.issuer = stripTrailingSlash(properties.getPublicUrl());
        this.resource = issuer + McpController.PATH;
        this.canonicalResource = canonical(resource);
        this.issuerPath = URI.create(issuer).getRawPath();
        this.resourcePath = URI.create(resource).getRawPath();
        this.consentUrl = stripTrailingSlash(frontendBaseUrl) + CONSENT_PATH;
    }

    public String issuer() {
        return issuer;
    }

    /** The address of {@code /mcp}: the audience of every token issued here. */
    public String resource() {
        return resource;
    }

    public String authorizationEndpoint() {
        return consentUrl;
    }

    public String tokenEndpoint() {
        return issuer + TOKEN_PATH;
    }

    public String revocationEndpoint() {
        return issuer + REVOKE_PATH;
    }

    /** The bare well-known form: a client given this address in the {@code 401} tries nothing else. */
    public String protectedResourceMetadataUrl() {
        return issuer + PROTECTED_RESOURCE_METADATA_PATH;
    }

    /** The path a client appends to a well-known prefix when it derives metadata from the issuer (RFC 8414). */
    public String issuerPath() {
        return issuerPath;
    }

    /** The path a client appends to a well-known prefix when it derives PRM from the resource (RFC 9728). */
    public String resourcePath() {
        return resourcePath;
    }

    /**
     * Whether a client's {@code resource} names this server. Compared after canonicalisation — a
     * trailing slash or an upper-case host is the same resource, and refusing it would fail one
     * client for spelling.
     */
    public boolean isOwnResource(String candidate) {
        String canonical = canonical(candidate);
        return canonical != null && canonical.equals(canonicalResource);
    }

    /**
     * Scheme and host lower-cased, the default port dropped, the trailing slash dropped; {@code null}
     * for anything that is not an absolute http(s) URL without a fragment.
     */
    static String canonical(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme) || uri.getHost() == null || uri.getRawFragment() != null) {
            return null;
        }
        int port = uri.getPort();
        boolean defaultPort = port == -1 || "https".equals(scheme) && port == 443 || "http".equals(scheme) && port == 80;
        String path = uri.getRawPath() == null ? "" : stripTrailingSlash(uri.getRawPath());
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port) + path
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    private static String stripTrailingSlash(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
