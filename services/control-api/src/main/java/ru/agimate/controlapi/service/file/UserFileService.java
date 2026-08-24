package ru.agimate.controlapi.service.file;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.TooManyRequestsStatusException;
import ru.agimate.controlapi.controller.manage.dto.files.FileListItemResponse;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.repositories.StoredFileRepository;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter;
import ru.agimate.controlapi.storage.FileLink;
import ru.agimate.controlapi.storage.FileStorageService;
import ru.agimate.controlapi.storage.NewFile;
import ru.agimate.controlapi.storage.SignedFileUrlService;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The owner's own files (docs/connectors/files.md): the upload, the listing and the manual deletion
 * behind {@code /manage/files}. Deliberately separate from {@link FileStorageService}, which is the
 * bytes-and-ownership layer used by connectors and gRPC — this layer needs HTTP DTOs and signed
 * links, and dragging those into {@code storage} would mix the layers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserFileService {

    private final StoredFileRepository storedFileRepository;
    private final FileStorageService fileStorageService;
    private final SignedFileUrlService signedFileUrlService;
    private final InboundRateLimiter rateLimiter;

    /** The user's own uploads; a client label, when given, becomes a suffix of it. */
    private static final String USER_ORIGIN = "user";

    /**
     * What a client may put into {@code origin} after the {@code user:} prefix. Narrow on purpose —
     * the value lands in a listing next to the trusted server-written ones ({@code telegram:<id>},
     * {@code media:<model>}), and the prefix is what keeps the two apart.
     */
    private static final Pattern CLIENT_ORIGIN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    /**
     * The user puts a file into the file layer under their own name; the {@code id} of the answer is
     * the {@code agf_} reference every consumer takes — webchat attachments ({@code parts}), tool
     * parameters, the {@code [[attach:…]]} marker.
     *
     * <p>Outside a transaction on purpose: {@link FileStorageService#store} commits the
     * {@code UPLOADING} row before the bytes go to the blob store, so that an interrupted upload is
     * findable by the sweeper — the class-level {@code readOnly} transaction would swallow both
     * saves.
     *
     * @param clientOrigin where the file came from in the client's own terms ({@code chat},
     *                     {@code board}); {@code null} — plain {@code user}. Never the whole
     *                     {@code origin}: see {@link #CLIENT_ORIGIN}
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public FileListItemResponse upload(UUID userId, MultipartFile file, String clientOrigin) {
        // Before touching the storage: the bucket's key is the user themselves.
        if (!rateLimiter.tryAcquire(InboundRateLimiter.Scope.FILE_UPLOAD, userId)) {
            throw new TooManyRequestsStatusException("File upload rate limit exceeded");
        }
        String origin = origin(clientOrigin);
        String mime = file.getContentType() != null && !file.getContentType().isBlank()
                ? file.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        // The content belongs to the user — only sizes and metadata go into the log.
        log.info("file upload by user {}: origin={}, mime={}, {} bytes", userId, origin, mime, file.getSize());

        StoredFile stored;
        try (InputStream content = file.getInputStream()) {
            // No agentId: the upload is not part of a run, and the recipient is chosen later — when
            // the file is attached to a message.
            stored = fileStorageService.store(NewFile.builder()
                    .userId(userId)
                    .origin(origin)
                    .name(file.getOriginalFilename())
                    .mime(mime)
                    .sizeBytes(file.getSize())
                    .build(), content);
        } catch (IOException e) {
            throw new BadRequestStatusException("Failed to read uploaded file: " + e.getMessage());
        }
        return FileListItemResponse.from(stored, signedFileUrlService.issue(FileLink.of(stored)));
    }

    /**
     * The user's files, freshest first, each with a freshly signed content link.
     *
     * @param agentId filter by the producing agent; {@code null} — every file of the user
     * @param name    case-insensitive substring of the name; {@code null} or blank — no filter
     */
    public Page<FileListItemResponse> list(UUID userId, UUID agentId, String name, int page, int size) {
        return storedFileRepository
                .findVisible(userId, agentId, blankToNull(name), LocalDateTime.now(), PageRequest.of(page, size))
                .map(file -> FileListItemResponse.from(file,
                        signedFileUrlService.issue(FileLink.of(file))));
    }

    /**
     * Removes a file before its TTL runs out — by expiring it, not by deleting it here. The blob and
     * the row then go through the one existing deletion path ({@code FileStorageService.purgeExpiredBatch},
     * within a minute): one place that touches the blob store, idempotent under retry. The file leaves
     * the listing immediately, since the listing filters on {@code expires_at}.
     */
    @Transactional
    public void delete(UUID userId, String fileId) {
        StoredFile file = fileStorageService.findReadable(userId, fileId)
                .orElseThrow(() -> new NotFoundStatusException("File", fileId));
        file.setExpiresAt(LocalDateTime.now());
        storedFileRepository.save(file);
        log.info("file {} expired on request by user {}", fileId, userId);
    }

    /**
     * The client names a place in its own UI, never the provenance itself — the {@code user:} prefix
     * is what stops an upload from passing itself off as a connector's file.
     */
    private static String origin(String clientOrigin) {
        String tag = blankToNull(clientOrigin);
        if (tag == null) {
            return USER_ORIGIN;
        }
        if (!CLIENT_ORIGIN.matcher(tag).matches()) {
            throw new BadRequestStatusException(
                    "origin must match " + CLIENT_ORIGIN.pattern() + ", got: " + tag);
        }
        return USER_ORIGIN + ":" + tag;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
