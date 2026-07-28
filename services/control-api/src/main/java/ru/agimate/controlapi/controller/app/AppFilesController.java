package ru.agimate.controlapi.controller.app;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.TooManyRequestsStatusException;
import ru.agimate.controlapi.controller.app.dto.FileResponse;
import ru.agimate.controlapi.controller.files.FileHttpResponses;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.security.AppPrincipal;
import ru.agimate.controlapi.service.AppService;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter;
import ru.agimate.controlapi.storage.FileStorageService;

import java.io.IOException;
import java.io.InputStream;

/**
 * The file layer for device apps (docs/connectors/files.md): uploading binary tool results
 * (screenshots and the like) and downloading files delivered to the device. The file's owner is the
 * application's user; foreign fileIds do not resolve.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(AppFilesController.PATH)
public class AppFilesController {

    public static final String PATH = AppRegistrationController.PATH + "/files";

    private final AppService appService;
    private final FileStorageService fileStorageService;
    private final InboundRateLimiter rateLimiter;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SuccessResponse<FileResponse> uploadFile(
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal AppPrincipal principal
    ) {
        // Before touching the database: the key (appId == connectionId) is already authenticated in the principal.
        if (!rateLimiter.tryAcquire(InboundRateLimiter.Scope.FILE_UPLOAD, principal.appId())) {
            throw new TooManyRequestsStatusException("File upload rate limit exceeded");
        }

        var app = appService.getApp(principal);
        String mime = file.getContentType() != null && !file.getContentType().isBlank()
                ? file.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        // The content belongs to the user — only sizes and metadata go into the log.
        log.info("File upload - app={}, mime={}, {} bytes", principal.appId(), mime, file.getSize());

        StoredFile stored;
        try (InputStream content = file.getInputStream()) {
            stored = fileStorageService.store(app.getUserId(), "app:" + app.getId(), mime,
                    file.getSize(), content, null);
        } catch (IOException e) {
            throw new BadRequestStatusException("Failed to read uploaded file: " + e.getMessage());
        }
        return SuccessResponse.ok(FileResponse.from(stored));
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<InputStreamResource> downloadFile(
            @PathVariable String fileId,
            @AuthenticationPrincipal AppPrincipal principal
    ) {
        var app = appService.getApp(principal);
        FileStorageService.FileContent content = fileStorageService.open(app.getUserId(), fileId);
        // Devices do not care about the headers — always an attachment (no inline rendering), and no cache is needed.
        return FileHttpResponses.serve(content, false, null);
    }
}
