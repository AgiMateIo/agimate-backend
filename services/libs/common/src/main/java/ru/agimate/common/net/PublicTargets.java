package ru.agimate.common.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * The single answer to «may this service open a connection to that address». Every outbound request
 * whose URL comes from a user — an agent's webhook, an LLM provider's base url, an MCP server, a
 * picture a provider answered with — goes through it.
 *
 * <p>Two halves, and both are needed. {@link #requireAllowed} vets a URL before the request is
 * built, which is what produces a comprehensible refusal. {@link #resolve} is the same check wired
 * into the HTTP client's own DNS lookup, which is what makes the refusal true: a check done
 * separately and a lookup done by the client are two different lookups, and a name is free to answer
 * with a public address to the first and a private one to the second (DNS rebinding). Only the
 * second half decides what the socket actually connects to.
 *
 * <p>{@code allowPrivate} exists for local development, where the interesting targets are on
 * loopback. It disables the guard entirely rather than softening it — a half-guard is harder to
 * reason about than none.
 */
public final class PublicTargets {

    private final boolean allowPrivate;

    public PublicTargets(boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    public boolean allowsPrivate() {
        return allowPrivate;
    }

    /** http(s), a host, and every address that host resolves to publicly routable. */
    public URI requireAllowed(String url) {
        return requireAllowed(url, false);
    }

    /**
     * The half of the check that costs nothing: the shape of the URL, plus the address itself when
     * the host <i>is</i> an address. For a name it stops there deliberately — this runs on writes
     * (an agent's webhook, a provider's base url), and resolving a name there would make saving a
     * form fail whenever DNS hiccups or the domain is not published yet, while proving nothing: the
     * answer that matters is the one given at request time, and {@link #resolve} is where it is
     * asked.
     */
    public URI requireSyntax(String url) {
        URI uri = requireShape(url, false);
        String host = uri.getHost();
        if (isAddressLiteral(host)) {
            try {
                requirePublic(InetAddress.getByName(stripBrackets(host)));
            } catch (UnknownHostException e) {
                throw new TargetNotAllowedException("Invalid URL");
            }
        }
        return uri;
    }

    /**
     * @param httpsOnly plain http is refused as well — for targets that carry a credential (OAuth
     *                  endpoints, a provider called with an api key), where http is a downgrade
     *                  rather than a preference
     */
    public URI requireAllowed(String url, boolean httpsOnly) {
        URI uri = requireShape(url, httpsOnly);
        if (allowPrivate) {
            return uri;
        }
        try {
            resolve(uri.getHost());
        } catch (UnknownHostException e) {
            throw new TargetNotAllowedException("Cannot resolve host: " + uri.getHost());
        }
        return uri;
    }

    private URI requireShape(String url, boolean httpsOnly) {
        URI uri = parse(url);
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new TargetNotAllowedException("URL must use http or https");
        }
        boolean https = scheme.equalsIgnoreCase("https");
        if (!https && !(scheme.equalsIgnoreCase("http") && !httpsOnly)) {
            throw new TargetNotAllowedException(httpsOnly
                    ? "URL must use https"
                    : "URL must use http or https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new TargetNotAllowedException("URL has no host");
        }
        if (uri.getUserInfo() != null) {
            // user@host in a URL is read differently by different parsers, so a check made here and
            // a connection made there can disagree about which of the two is the host.
            throw new TargetNotAllowedException("URL must not carry userinfo");
        }
        return uri;
    }

    /** An IP written out, rather than a name — the one case an address check needs no lookup. */
    private static boolean isAddressLiteral(String host) {
        if (host.startsWith("[")) {
            return true;
        }
        int dots = 0;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') {
                dots++;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return dots == 3;
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    /**
     * The lookup an HTTP client's DNS hook performs: every address of the name is vetted, and the
     * connection may then use only these.
     *
     * @throws TargetNotAllowedException any of the addresses is not publicly routable — refusing the
     *                                   name rather than filtering its addresses, because a name
     *                                   that answers with both is answering ambiguously
     */
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (allowPrivate) {
            return addresses;
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new TargetNotAllowedException("Host resolves to an address that is not allowed: " + host);
            }
        }
        return addresses;
    }

    public void requirePublic(InetAddress address) {
        if (!allowPrivate && !isPublic(address)) {
            throw new TargetNotAllowedException("Connection to a non-public address is not allowed");
        }
    }

    /**
     * Everything an operator's network might route somewhere private. Beyond what {@link InetAddress}
     * classifies: {@code 0.0.0.0/8} («this network»), {@code 100.64.0.0/10} (carrier-grade NAT — a
     * routable-looking range that cloud providers do put infrastructure on) and IPv6 unique-local
     * {@code fc00::/7}, which {@code isSiteLocalAddress} does not cover. IPv4-mapped IPv6 needs no
     * case of its own: {@code InetAddress} hands back an {@link Inet4Address} for it.
     */
    public static boolean isPublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] octets = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = octets[0] & 0xff;
            int second = octets[1] & 0xff;
            return first != 0 && !(first == 100 && second >= 64 && second <= 127);
        }
        return (octets[0] & 0xfe) != 0xfc;
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw new TargetNotAllowedException("URL is empty");
        }
        try {
            return new URI(url.strip());
        } catch (URISyntaxException e) {
            throw new TargetNotAllowedException("Invalid URL");
        }
    }
}
