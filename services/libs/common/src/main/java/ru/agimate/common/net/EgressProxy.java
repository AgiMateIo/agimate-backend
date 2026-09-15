package ru.agimate.common.net;

import com.google.common.net.InetAddresses;
import com.google.common.net.InternetDomainName;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * The installation's HTTP proxy for hosts that do not answer from where it runs (geo-blocked LLM
 * providers, MCP servers, Telegram) — and only for those: a request goes through the proxy when its
 * host is on the operator's list, and directly otherwise.
 *
 * <p>A {@link ProxySelector}, because both outbound clients take one natively (Apache
 * {@code setProxySelector}, OkHttp {@code proxySelector}), so no service carries routing logic of its
 * own.
 *
 * <p>A covered host is looked up and connected to by the proxy, which moves the address guard there:
 * see {@link PublicTargets#requireAllowed(String, boolean)}. That is acceptable only because the list
 * is the operator's, which is why {@code *} and wildcards over a public suffix ({@code *.com},
 * {@code *.github.io} — anyone can own a name under those) stop the start.
 */
@Slf4j
public final class EgressProxy extends ProxySelector {

    public static final EgressProxy NONE = new EgressProxy(null, null, null, List.of());

    /** What an unencoded {@code @} or {@code #} in a password turns into — said instead of «no host». */
    private static final String ENCODING_HINT = " (special characters of the password must be percent-encoded: @ is %40)";

    private final Proxy proxy;
    private final String username;
    private final String password;
    /** Lower-cased exact names, and {@code .domain} for {@code *.domain}. */
    private final List<String> hosts;

    private EgressProxy(Proxy proxy, String username, String password, List<String> hosts) {
        this.proxy = proxy;
        this.username = username;
        this.password = password;
        this.hosts = hosts;
    }

    /**
     * @param url   {@code http://user:password@host:port}; blank means no proxy, whatever the hosts —
     *              the list may live in shared settings while only some installations have a proxy
     * @throws IllegalStateException the url is set but unusable, or nothing would go through it
     */
    public static EgressProxy of(String url, List<String> hosts) {
        List<String> patterns = hosts == null ? List.of()
                : hosts.stream().map(String::strip).filter(host -> !host.isEmpty()).toList();
        if (url == null || url.isBlank()) {
            if (!patterns.isEmpty()) {
                // Loud, because a misspelt url variable otherwise shows up only as 403s from the providers.
                log.warn("Egress proxy: hosts {} are listed but no url is set — they go direct", patterns);
            }
            return NONE;
        }
        if (patterns.isEmpty()) {
            throw new IllegalStateException("Egress proxy url is set but no hosts are listed");
        }

        URI uri = parse(url.strip());
        String[] credentials = uri.getUserInfo() == null ? new String[0] : uri.getUserInfo().split(":", 2);
        if (credentials.length != 2 || credentials[0].isEmpty() || credentials[1].isEmpty()) {
            throw new IllegalStateException("Egress proxy url must carry a username and a password");
        }
        // Unresolved: the proxy's name is looked up by the client's own resolver, which is the guard.
        Proxy proxy = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort()));

        EgressProxy egress = new EgressProxy(proxy, credentials[0], credentials[1],
                patterns.stream().map(EgressProxy::normalize).toList());
        log.info("Egress proxy: {}", egress);
        return egress;
    }

    public boolean enabled() {
        return proxy != null;
    }

    public boolean covers(String host) {
        if (host == null || hosts.isEmpty()) {
            return false;
        }
        String name = host.toLowerCase(Locale.ROOT);
        return hosts.stream().anyMatch(pattern -> pattern.startsWith(".") ? name.endsWith(pattern) : name.equals(pattern));
    }

    @Override
    public List<Proxy> select(URI uri) {
        return covers(uri.getHost()) ? List.of(proxy) : List.of(Proxy.NO_PROXY);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress address, IOException e) {
        // One proxy and no fallback to try: the failure reaches the caller as it is.
    }

    public Proxy proxy() {
        return proxy;
    }

    public String host() {
        return ((InetSocketAddress) proxy.address()).getHostString();
    }

    public int port() {
        return ((InetSocketAddress) proxy.address()).getPort();
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    /** Address and hosts only: the password must not reach a log line. */
    @Override
    public String toString() {
        return enabled() ? host() + ":" + port() + " for " + hosts : "none";
    }

    private static URI parse(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Egress proxy url is invalid" + ENCODING_HINT);
        }
        // TLS to the proxy itself is something neither client speaks without wrappers.
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("Egress proxy url must use http");
        }
        if (uri.getHost() == null) {
            throw new IllegalStateException("Egress proxy url has no host" + ENCODING_HINT);
        }
        if (uri.getPort() == -1) {
            throw new IllegalStateException("Egress proxy url must name a port");
        }
        return uri;
    }

    private static String normalize(String pattern) {
        String host = pattern.toLowerCase(Locale.ROOT);
        if (!host.startsWith("*.")) {
            // A url or host:port here would be accepted and never match, sending the host direct.
            if (!InternetDomainName.isValid(host) && !InetAddresses.isInetAddress(host)) {
                throw new IllegalStateException("Egress proxy host must be a bare host name: " + pattern);
            }
            return host;
        }
        String domain = host.substring(2);
        if (!InternetDomainName.isValid(domain) || !InternetDomainName.from(domain).isUnderPublicSuffix()) {
            throw new IllegalStateException("Egress proxy host pattern not allowed: " + pattern
                    + " (a wildcard must sit under a registrable domain)");
        }
        return "." + domain;
    }
}
