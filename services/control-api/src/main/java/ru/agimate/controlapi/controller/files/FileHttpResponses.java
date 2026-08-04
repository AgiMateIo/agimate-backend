package ru.agimate.controlapi.controller.files;

import lombok.experimental.UtilityClass;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.FileStorageService;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * The single way file contents are served over HTTP. The mime is the client's (set at upload time), so
 * active content always degrades to octet-stream, while nosniff plus a CSP sandbox close off stored
 * XSS in the service's origin. {@code inline=true} means displayable types (images) are rendered by
 * the browser ({@code <img src>} on signed links), and everything else is an attachment.
 */
@UtilityClass
public class FileHttpResponses {

    /** MIME types the browser could execute in the service's origin — served as octet-stream. */
    private static final Set<String> ACTIVE_CONTENT_TYPES = Set.of(
            "text/html", "application/xhtml+xml", "image/svg+xml", "application/xml", "text/xml",
            "text/javascript", "application/javascript");

    /** @param cacheControl null — no cache header (private answers behind an auth header) */
    public static ResponseEntity<InputStreamResource> serve(FileStorageService.FileContent content,
                                                            boolean inline, CacheControl cacheControl) {
        MediaType mediaType = safeMediaType(content.file().getMime());
        boolean render = inline && mediaType.getType().equals("image");
        // The stored name when the producer knew one, otherwise the id; a name may be non-ASCII, so
        // the header is built by Spring (filename + RFC 5987 filename*), never by concatenation.
        String filename = content.file().getName() != null
                ? content.file().getName() : FileIds.external(content.file().getId());
        ContentDisposition disposition = (render
                ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'");
        if (cacheControl != null) {
            builder.cacheControl(cacheControl);
        }
        return builder
                .contentType(mediaType)
                .contentLength(content.file().getSizeBytes())
                .body(new InputStreamResource(content.content()));
    }

    private static MediaType safeMediaType(String mime) {
        try {
            MediaType parsed = MediaType.parseMediaType(mime);
            if (ACTIVE_CONTENT_TYPES.contains(parsed.getType() + "/" + parsed.getSubtype())) {
                return MediaType.APPLICATION_OCTET_STREAM;
            }
            return parsed;
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
