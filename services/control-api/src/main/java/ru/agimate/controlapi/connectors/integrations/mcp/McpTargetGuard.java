package ru.agimate.controlapi.connectors.integrations.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF guard for every host the MCP connector talks to. Not just the server the user typed: OAuth
 * discovery walks to addresses taken out of someone else's response — protected resource metadata,
 * authorisation server metadata, the token endpoint — and by those addresses travel an authorisation
 * code and tokens rather than a picture.
 *
 * <p>Resolving on every call (rather than once, when the connection is created) narrows the
 * DNS-rebinding window without closing it: the resolution done here and the one done inside the HTTP
 * client are two different lookups.
 */
@Component
public class McpTargetGuard {

    /** Whether targets on private or loopback addresses are allowed (local development only). */
    private final boolean allowPrivateTargets;

    public McpTargetGuard(@Value("${app.connectors.mcp.allow-private-targets:false}") boolean allowPrivateTargets) {
        this.allowPrivateTargets = allowPrivateTargets;
    }

    public boolean allowsPrivateTargets() {
        return allowPrivateTargets;
    }

    /** http(s) and a publicly routable address. */
    public void requireAllowed(String url) {
        requireAllowed(url, false);
    }

    /**
     * @param httpsOnly the authorisation server's endpoints must be HTTPS (spec); plain http is
     *                  tolerated only under the local-development flag
     */
    public void requireAllowed(String url, boolean httpsOnly) {
        if (allowPrivateTargets) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Invalid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new ConnectorException("URL must use http or https");
        }
        boolean https = scheme.equalsIgnoreCase("https");
        if (!https && !(scheme.equalsIgnoreCase("http") && !httpsOnly)) {
            throw new ConnectorException(httpsOnly
                    ? "Authorization server endpoints must use https"
                    : "URL must use http or https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ConnectorException("URL has no host");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new ConnectorException("Cannot resolve host: " + host);
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new ConnectorException("URL resolves to a non-public address and is not allowed");
            }
        }
    }

    private static boolean isBlocked(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        // IPv6 unique-local (fc00::/7) — InetAddress.isSiteLocalAddress does not cover it.
        return address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc;
    }
}
