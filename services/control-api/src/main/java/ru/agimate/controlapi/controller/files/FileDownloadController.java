package ru.agimate.controlapi.controller.files;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.controlapi.config.FileStorageProperties;
import ru.agimate.controlapi.storage.FileStorageService;
import ru.agimate.controlapi.storage.SignedFileUrlService;

/**
 * Скачивание файла по подписанной ссылке (docs/connectors/files.md): браузерный доступ к
 * webchat-вложениям без {@code Authorization}-заголовка ({@code <img src>}). Аутентификация —
 * HMAC-подпись {@code exp+sig} ({@link SignedFileUrlService}), поэтому путь публичный в
 * {@code SecurityConfig}; владение проверено при выдаче ссылки.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(FileDownloadController.PATH)
public class FileDownloadController {

    public static final String PATH = "/files";

    private final FileStorageService fileStorageService;
    private final SignedFileUrlService signedFileUrlService;
    private final FileStorageProperties fileStorageProperties;

    @GetMapping("/{fileId}")
    public ResponseEntity<InputStreamResource> download(
            @PathVariable String fileId,
            @RequestParam long exp,
            @RequestParam String sig
    ) {
        if (!signedFileUrlService.verify(fileId, exp, sig)) {
            throw new ForbiddenStatusException("File link is invalid or expired");
        }
        FileStorageService.FileContent content = fileStorageService.openSigned(fileId);
        // Контент по agf_-id неизменяем; кеш — приватный и не дольше срока жизни ссылки.
        return FileHttpResponses.serve(content, true,
                CacheControl.maxAge(fileStorageProperties.getUrlTtl()).cachePrivate());
    }
}
