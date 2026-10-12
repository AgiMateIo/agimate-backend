package ru.agimate.controlapi.service.mcp.oauth;

import lombok.experimental.UtilityClass;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** How a requested {@code redirect_uri} is matched against the registered ones. */
@UtilityClass
public class RedirectUris {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final Set<String> FORBIDDEN_SCHEMES =
            Set.of("javascript", "data", "file", "vbscript", "about", "blob", "filesystem");

    /** Absolute, no fragment, not a scheme that runs code or reads disk; empty for anything else. */
    public static Optional<URI> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || uri.getRawFragment() != null
                    || FORBIDDEN_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
                return Optional.empty();
            }
            return Optional.of(uri);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Whether a client may register this address at all. MCP requires every redirect URI to be either
     * localhost or HTTPS: plain {@code http} elsewhere would carry the code in the clear, and the
     * consent page would send the browser there from our own domain. Private-use schemes
     * ({@code cursor://}) are out too — no client identified by a metadata document needs one.
     *
     * @param allowInsecureHttp a local stand, where private targets are allowed: plain http anywhere
     */
    public static boolean isAllowed(URI uri, boolean allowInsecureHttp) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null) {
            return false;
        }
        return "https".equals(scheme) || "http".equals(scheme) && (allowInsecureHttp || isLoopback(uri));
    }

    /**
     * Exact string match, except loopback: a native client listens on whatever port it got, so the
     * port of a loopback address is not part of the match (RFC 8252, 7.3).
     */
    public static boolean matches(String registered, String requested) {
        if (registered.equals(requested)) {
            return true;
        }
        Optional<URI> left = parse(registered);
        Optional<URI> right = parse(requested);
        if (left.isEmpty() || right.isEmpty() || !isLoopback(left.get()) || !isLoopback(right.get())) {
            return false;
        }
        return withoutPort(left.get()).equals(withoutPort(right.get()));
    }

    public static boolean isLoopback(URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
    }

    /** The browser comes back to an application on the user's own machine, not to a website. */
    public static boolean isLocalApplication(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        return isLoopback(uri) || !"https".equals(scheme) && !"http".equals(scheme);
    }

    private static String withoutPort(URI uri) {
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (uri.getRawPath() == null ? "" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }
}
