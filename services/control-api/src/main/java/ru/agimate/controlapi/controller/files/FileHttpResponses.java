package ru.agimate.controlapi.controller.files;

import lombok.experimental.UtilityClass;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.FileStorageService;

import java.util.Set;

/**
 * Единая отдача файлового содержимого по HTTP. mime — клиентский (задан при аплоаде), поэтому
 * активный контент всегда деградирует до octet-stream, а nosniff + CSP-sandbox закрывают
 * stored-XSS в origin'е сервиса. {@code inline=true} — отображаемые типы (изображения) рендерятся
 * браузером ({@code <img src>} на подписанных ссылках), остальное — attachment.
 */
@UtilityClass
public class FileHttpResponses {

    /** MIME-типы, которые браузер может исполнить в origin'е сервиса — отдаются как octet-stream. */
    private static final Set<String> ACTIVE_CONTENT_TYPES = Set.of(
            "text/html", "application/xhtml+xml", "image/svg+xml", "application/xml", "text/xml",
            "text/javascript", "application/javascript");

    /** @param cacheControl null — без кеш-заголовка (приватные ответы под auth-заголовком) */
    public static ResponseEntity<InputStreamResource> serve(FileStorageService.FileContent content,
                                                            boolean inline, CacheControl cacheControl) {
        MediaType mediaType = safeMediaType(content.file().getMime());
        boolean render = inline && mediaType.getType().equals("image");
        String disposition = (render ? "inline" : "attachment")
                + "; filename=\"" + FileIds.external(content.file().getId()) + "\"";
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
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
