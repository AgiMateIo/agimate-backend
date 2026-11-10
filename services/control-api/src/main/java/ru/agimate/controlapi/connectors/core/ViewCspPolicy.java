package ru.agimate.controlapi.connectors.core;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.dto.ViewCsp;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The installation's policy for a view's declared external domains ({@code _meta.ui.csp}): the
 * server asks, this allow-list decides. A value is granted only as a plain {@code https} origin and
 * only when it is listed for its category; anything else is dropped. Empty lists (the default) are
 * the strict policy the platform shipped with — no external domains at all.
 *
 * <p>The allow-list is normalized as it is bound, so a malformed entry is reported at startup and
 * not on every view read.
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "app.views.csp")
public class ViewCspPolicy {

    @Getter
    private List<String> allowedResourceDomains = new ArrayList<>();
    @Getter
    private List<String> allowedConnectDomains = new ArrayList<>();
    private Set<String> resourceOrigins = Set.of();
    private Set<String> connectOrigins = Set.of();

    public void setAllowedResourceDomains(List<String> domains) {
        allowedResourceDomains = domains == null ? List.of() : domains;
        resourceOrigins = allowedOrigins(allowedResourceDomains);
    }

    public void setAllowedConnectDomains(List<String> domains) {
        allowedConnectDomains = domains == null ? List.of() : domains;
        connectOrigins = allowedOrigins(allowedConnectDomains);
    }

    /**
     * @param declared domains as the server declared them; null means no external domains
     * @return the granted domains, or null when nothing was granted
     */
    public ViewCsp grant(ViewCsp declared) {
        if (declared == null) {
            return null;
        }
        List<String> resource = granted(declared.resourceDomains(), resourceOrigins);
        List<String> connect = granted(declared.connectDomains(), connectOrigins);
        if (resource.isEmpty() && connect.isEmpty()) {
            return null;
        }
        return new ViewCsp(
                resource.isEmpty() ? null : resource,
                connect.isEmpty() ? null : connect);
    }

    /** Exact origins in the allow-list, normalized; a malformed entry grants nothing. */
    private static Set<String> allowedOrigins(List<String> allowed) {
        Set<String> origins = new LinkedHashSet<>();
        for (String value : allowed) {
            String origin = originOf(value);
            if (origin == null) {
                log.warn("Ignoring view CSP allow-list entry that is not an https origin: {}", value);
            } else {
                origins.add(origin);
            }
        }
        return origins;
    }

    private static List<String> granted(List<String> declared, Set<String> allowed) {
        if (declared == null || declared.isEmpty() || allowed.isEmpty()) {
            return List.of();
        }
        Set<String> granted = new LinkedHashSet<>();
        for (String raw : declared) {
            String origin = originOf(raw);
            if (origin == null) {
                continue;
            }
            if (allowed.contains(origin)) {
                granted.add(origin);
            } else {
                log.debug("View CSP domain not allowed by the installation: {}", origin);
            }
        }
        return List.copyOf(granted);
    }

    /**
     * A CSP source is accepted only as a plain https origin: no userinfo, no path beyond "/", no
     * query, no fragment, no wildcard. Returns the normalized origin or null.
     */
    private static String originOf(String raw) {
        if (raw == null || raw.isBlank() || raw.indexOf('*') >= 0) {
            return null;
        }
        try {
            URI uri = new URI(raw.trim());
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            if (uri.getHost() == null || uri.getRawUserInfo() != null) {
                return null;
            }
            String path = uri.getRawPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                return null;
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return null;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return uri.getPort() == -1 ? "https://" + host : "https://" + host + ":" + uri.getPort();
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
