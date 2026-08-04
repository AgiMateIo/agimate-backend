package ru.agimate.controlapi.service.file;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.manage.dto.files.FileListItemResponse;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.repositories.StoredFileRepository;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.FileStorageService;
import ru.agimate.controlapi.storage.SignedFileUrlService;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The owner's view of their own files (docs/connectors/files.md): the listing behind
 * {@code /manage/files} and manual deletion. Deliberately separate from {@link FileStorageService},
 * which is the bytes-and-ownership layer used by connectors and gRPC — a listing needs HTTP DTOs and
 * signed links, and dragging those into {@code storage} would mix the layers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserFileService {

    private final StoredFileRepository storedFileRepository;
    private final FileStorageService fileStorageService;
    private final SignedFileUrlService signedFileUrlService;

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
                        signedFileUrlService.issue(FileIds.external(file.getId()))));
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
