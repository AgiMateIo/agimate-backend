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
 * Downloading a file by a signed link (docs/connectors/files.md): browser access to webchat
 * attachments without an {@code Authorization} header ({@code <img src>}). Authentication is the HMAC
 * signature {@code exp+sig} ({@link SignedFileUrlService}), which is why the path is public in
 * {@code SecurityConfig}; ownership was checked when the link was issued.
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
        // Content behind an agf_ id is immutable; the cache is private and no longer than the link's lifetime.
        return FileHttpResponses.serve(content, true,
                CacheControl.maxAge(fileStorageProperties.getUrlTtl()).cachePrivate());
    }
}
