package ru.agimate.controlapi.controller.files;

import lombok.experimental.UtilityClass;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import ru.agimate.controlapi.storage.FileContentHeaders;
import ru.agimate.controlapi.storage.FileLink;
import ru.agimate.controlapi.storage.FileStorageService;

/**
 * The single way file contents are served over HTTP by control-api itself. What the response claims
 * the bytes are comes from {@link FileContentHeaders} — shared with presigned links, so active
 * content degrades to octet-stream on both paths; nosniff plus a CSP sandbox are added here, and only
 * here, because they close off stored XSS in the service's own origin.
 */
@UtilityClass
public class FileHttpResponses {

    /** @param cacheControl null — no cache header (private answers behind an auth header) */
    public static ResponseEntity<InputStreamResource> serve(FileStorageService.FileContent content,
                                                            boolean inline, CacheControl cacheControl) {
        FileLink link = FileLink.of(content.file());
        MediaType mediaType = FileContentHeaders.contentType(link.mime());
        ContentDisposition disposition = FileContentHeaders.contentDisposition(link, mediaType, inline);
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
}
