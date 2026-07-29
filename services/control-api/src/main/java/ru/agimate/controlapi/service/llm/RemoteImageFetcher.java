package ru.agimate.controlapi.service.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import ru.agimate.controlapi.service.llm.MediaTransport.InputImage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
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
 * forgery primitive pointed at our own network.
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
        requirePublicHttps(uri);
        try {
            return client().get()
                    .uri(uri)
                    .exchange((request, response) -> read(response, uri), false);
        } catch (ResourceAccessException e) {
            throw new MediaInferenceException("failed to download the generated image: " + e.getMessage());
        }
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

    /**
     * The link must be public https. The host is resolved and checked here rather than pattern-matched:
     * a name that resolves into the private range is the whole point of an SSRF attempt.
     */
    static void requirePublicHttps(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new MediaInferenceException("provider's image link is not https");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                        || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new MediaInferenceException("provider's image link points to a private address");
                }
            }
        } catch (UnknownHostException e) {
            throw new MediaInferenceException("provider's image link host is unresolvable");
        }
    }

    /** Redirects are not followed: a redirect would move the request to an address nothing has vetted. */
    private static RestClient client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                    throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return RestClient.builder().requestFactory(factory).build();
    }
}
