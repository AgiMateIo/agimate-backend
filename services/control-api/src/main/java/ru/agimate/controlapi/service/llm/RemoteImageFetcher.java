package ru.agimate.controlapi.service.llm;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import ru.agimate.controlapi.service.llm.MediaTransport.InputImage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * Downloads a generated picture from the link a provider answered with (the Polza dialect and every
 * async media API return a URL, not bytes).
 *
 * <p>The address comes from a provider's response, so it is treated as untrusted input: https only,
 * no redirects, no private or loopback addresses, a hard size ceiling and a required {@code image/*}
 * content type. Without those a compromised or merely sloppy provider turns this into a request
 * forgery primitive pointed at our own network — and providers are created by users, with a base URL
 * of their choosing, so «the provider is trusted» is not an assumption available here.
 *
 * <p>The address check sits in the client's DNS resolver rather than in front of the call, so the
 * connection can only reach the addresses that were vetted; see {@link #resolvePublicOnly}.
 */
@Component
@Slf4j
public class RemoteImageFetcher {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(2);
    /** Same ceiling as an input picture: the result is buffered in the heap in full. */
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    /**
     * @throws MediaInferenceException the link is not fetchable, points inside the network, is not an
     *                                 image, or is larger than the ceiling
     */
    public InputImage fetch(String url) {
        URI uri = parse(url);
        requireHttps(uri);
        try {
            return client().get()
                    .uri(uri)
                    .exchange((request, response) -> read(response, uri), false);
        } catch (ResourceAccessException e) {
            // The address check lives in the DNS resolver, so its refusal arrives wrapped.
            if (unwrapRefusal(e) instanceof MediaInferenceException refusal) {
                throw refusal;
            }
            throw new MediaInferenceException("failed to download the generated image: " + e.getMessage());
        }
    }

    private static Throwable unwrapRefusal(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof MediaInferenceException) {
                return cause;
            }
        }
        return e;
    }

    private static InputImage read(ClientHttpResponse response, URI uri) throws IOException {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new MediaInferenceException("provider's image link returned "
                    + response.getStatusCode().value());
        }
        String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        String mime = contentType == null ? null : contentType.split(";")[0].trim();
        if (mime == null || !mime.startsWith("image/")) {
            throw new MediaInferenceException("provider's image link returned " + mime + ", not an image");
        }
        long declared = response.getHeaders().getContentLength();
        if (declared > MAX_BYTES) {
            throw new MediaInferenceException("generated image is too large (" + declared
                    + " bytes, limit " + MAX_BYTES + ")");
        }
        byte[] bytes = readCapped(response.getBody());
        log.debug("downloaded generated image {} bytes from {}", bytes.length, uri.getHost());
        return new InputImage(mime, bytes);
    }

    /** Reads with the ceiling enforced on the actual stream — a missing or lying Content-Length is not a way in. */
    private static byte[] readCapped(InputStream body) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = body.read(chunk)) != -1) {
            if (buffer.size() + read > MAX_BYTES) {
                throw new MediaInferenceException("generated image exceeds " + MAX_BYTES + " bytes");
            }
            buffer.write(chunk, 0, read);
        }
        if (buffer.size() == 0) {
            throw new MediaInferenceException("provider's image link returned an empty body");
        }
        return buffer.toByteArray();
    }

    private static URI parse(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new MediaInferenceException("provider returned an unusable image link");
        }
    }

    static void requireHttps(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new MediaInferenceException("provider's image link is not https");
        }
    }

    /**
     * Resolves the name once and lets the connection use <b>only</b> the addresses vetted here.
     * Checking the address separately and then letting the client resolve again would be two
     * different lookups: a name can answer with a public address to the check and a private one to
     * the connection (DNS rebinding), which is exactly what this guard exists to stop.
     */
    static InetAddress[] resolvePublicOnly(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress address : addresses) {
            requirePublic(address);
        }
        return addresses;
    }

    /** Not a lambda: {@link DnsResolver} has a second method, and canonical-name lookup stays default. */
    private static final DnsResolver PUBLIC_ONLY_DNS = new DnsResolver() {
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            return resolvePublicOnly(host);
        }

        @Override
        public String resolveCanonicalHostname(String host) throws UnknownHostException {
            return SystemDefaultDnsResolver.INSTANCE.resolveCanonicalHostname(host);
        }
    };

    static void requirePublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            throw new MediaInferenceException("provider's image link points to a private address");
        }
    }

    /** Redirects are not followed: a redirect would move the request to an address nothing has vetted. */
    private static RestClient client() {
        PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(PUBLIC_ONLY_DNS)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT.toMillis()))
                        .build())
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connections)
                .disableRedirectHandling()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofMilliseconds(READ_TIMEOUT.toMillis()))
                        .build())
                .build();
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
    }
}
