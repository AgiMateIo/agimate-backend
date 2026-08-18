package ru.agimate.controlapi.storage;

import lombok.experimental.UtilityClass;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * How file contents are presented to a browser. The mime is the client's (set at upload time), so
 * active content always degrades to octet-stream — the one rule that must hold on both delivery
 * paths: the bytes served by control-api
 * ({@link ru.agimate.controlapi.controller.files.FileHttpResponses}) and a presigned direct link to
 * the object store, where these values travel as {@code response-content-*} overrides.
 */
@UtilityClass
public class FileContentHeaders {

    /** MIME types the browser could execute in the serving origin — served as octet-stream. */
    private static final Set<String> ACTIVE_CONTENT_TYPES = Set.of(
            "text/html", "application/xhtml+xml", "image/svg+xml", "application/xml", "text/xml",
            "text/javascript", "application/javascript");

    public static MediaType contentType(String mime) {
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

    /**
     * The stored name when the producer knew one, otherwise the id; a name may be non-ASCII, so the
     * header is built by Spring (filename + RFC 5987 filename*), never by concatenation.
     *
     * @param contentType the type the response will claim — the already degraded one, not the stored
     *                    mime, so that active content never renders inline
     * @param allowInline whether displayable types may be rendered by the browser ({@code <img src>}
     *                    on signed links); everything else is an attachment either way
     */
    public static ContentDisposition contentDisposition(FileLink link, MediaType contentType,
                                                        boolean allowInline) {
        boolean render = allowInline && contentType.getType().equals("image");
        String filename = link.name() != null ? link.name() : link.fileId();
        return (render ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(filename, StandardCharsets.UTF_8)
                .build();
    }
}
