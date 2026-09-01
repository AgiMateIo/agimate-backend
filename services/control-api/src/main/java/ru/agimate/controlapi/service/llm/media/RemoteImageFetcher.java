package ru.agimate.controlapi.service.llm.media;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import ru.agimate.common.net.TargetNotAllowedException;
import ru.agimate.controlapi.service.http.PublicOnlyHttp;
import ru.agimate.controlapi.service.llm.media.MediaTransport.InputImage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/**
 * Downloads a generated picture from the link a provider answered with (the Polza dialect and every
 * async media API return a URL, not bytes).
 *
 * <p>The address comes from a provider's response, so it is treated as untrusted input: https only,
 * a hard size ceiling, a required {@code image/*} content type, and the network guarantees of
 * {@link PublicOnlyHttp} — no redirects, and only vetted addresses reachable. Without those a
 * compromised or merely sloppy provider turns this into a request forgery primitive pointed at our
 * own network — and providers are created by users, with a base URL of their choosing, so «the
 * provider is trusted» is not an assumption available here.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RemoteImageFetcher {

    private static final Duration READ_TIMEOUT = Duration.ofMinutes(2);
    /** Same ceiling as an input picture: the result is buffered in the heap in full. */
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    private final PublicOnlyHttp http;

    /**
     * @throws MediaInferenceException the link is not fetchable, points inside the network, is not an
     *                                 image, or is larger than the ceiling
     */
    public InputImage fetch(String url) {
        URI uri = parse(url);
        requireHttps(uri);
        try {
            return http.restClient(READ_TIMEOUT).build().get()
                    .uri(uri)
                    .exchange((request, response) -> read(response, uri), false);
        } catch (ResourceAccessException e) {
            // The address check lives in the DNS resolver, so its refusal arrives wrapped.
            if (refusal(e) != null) {
                throw new MediaInferenceException("provider's image link points to an address that is not allowed");
            }
            throw new MediaInferenceException("failed to download the generated image: " + e.getMessage());
        }
    }

    private static TargetNotAllowedException refusal(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof TargetNotAllowedException refusal) {
                return refusal;
            }
        }
        return null;
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
}
