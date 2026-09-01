package ru.agimate.controlapi.service.http;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.agimate.common.net.PublicTargets;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * Every outbound HTTP call of control-api whose address a user chose — an agent's webhook, an LLM
 * provider's base url, an MCP server, a picture a provider answered with — is made with a client
 * from here.
 *
 * <p>Two properties, and the second is the one that matters: no redirects (a {@code 302} moves the
 * request to an address nothing vetted), and name resolution done by {@link PublicTargets}, so the
 * connection can reach only the addresses that were vetted. A URL checked before the request and a
 * name resolved by the client are two separate lookups, and a name may answer differently to each.
 *
 * <p>The connection pool is shared across callers; only the response timeout differs between them,
 * which is why {@link #requestFactory} takes it and the connect timeout is fixed.
 */
@Component
public class PublicOnlyHttp {

    /** Uniform across callers — none of them has a reason to wait longer for a TCP connect. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * The pool is shared by everything here, and one of its users — media generation — holds a
     * connection for minutes at a time. Apache's defaults (25 total, 5 per route) would let a
     * handful of pictures starve model discovery and MCP calls of connections entirely.
     */
    private static final int MAX_CONNECTIONS = 200;
    private static final int MAX_CONNECTIONS_PER_ROUTE = 50;

    private final PublicTargets targets;
    private final CloseableHttpClient httpClient;

    public PublicOnlyHttp(@Value("${app.net.allow-private-targets:false}") boolean allowPrivateTargets) {
        this.targets = new PublicTargets(allowPrivateTargets);
        this.httpClient = buildClient(this.targets);
    }

    public PublicTargets targets() {
        return targets;
    }

    /** Vets a URL before a request is built — the comprehensible half of the guard. */
    public URI requireAllowed(String url) {
        return targets.requireAllowed(url);
    }

    /** The shape of a URL and, when the host is an address, the address — without a lookup. */
    public URI requireSyntax(String url) {
        return targets.requireSyntax(url);
    }

    public URI requireAllowed(String url, boolean httpsOnly) {
        return targets.requireAllowed(url, httpsOnly);
    }

    public ClientHttpRequestFactory requestFactory(Duration readTimeout) {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    public RestClient.Builder restClient(Duration readTimeout) {
        return RestClient.builder().requestFactory(requestFactory(readTimeout));
    }

    private static CloseableHttpClient buildClient(PublicTargets targets) {
        PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(publicOnlyDns(targets))
                .setMaxConnTotal(MAX_CONNECTIONS)
                .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT.toMillis()))
                        .build())
                .build();
        return HttpClients.custom()
                .setConnectionManager(connections)
                .disableRedirectHandling()
                .build();
    }

    /** Not a lambda: {@link DnsResolver} has a second method, and canonical-name lookup stays default. */
    private static DnsResolver publicOnlyDns(PublicTargets targets) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return targets.resolve(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                return SystemDefaultDnsResolver.INSTANCE.resolveCanonicalHostname(host);
            }
        };
    }
}
