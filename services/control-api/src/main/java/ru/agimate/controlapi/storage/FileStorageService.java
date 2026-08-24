package ru.agimate.controlapi.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.util.UUIDUtils;
import ru.agimate.controlapi.config.FileStorageProperties;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.enums.FileReferenceKind;
import ru.agimate.controlapi.database.enums.FileStatus;
import ru.agimate.controlapi.database.repositories.StoredFileRepository;
import ru.agimate.controlapi.service.file.FileReferenceService;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The connectors' file layer (docs/connectors/files.md): metadata in {@code files}, bytes in
 * {@link BlobStore} under the key {@code userId/agf_<id>}. Ownership is {@code user_id}: a foreign
 * fileId does not resolve by construction.
 *
 * <p>{@link #store} is deliberately NOT transactional: between inserting the UPLOADING row and
 * moving it to READY there is a network upload to S3, and a transaction must not be held across it.
 * A failed upload leaves an UPLOADING row — that row (and any orphaned blob) is swept by
 * {@link #purgeExpiredBatch} on age.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final StoredFileRepository storedFileRepository;
    private final BlobStore blobStore;
    private final FileStorageProperties props;
    private final FileReferenceService fileReferenceService;

    /** Metadata plus a stream of the contents; the caller closes the stream. */
    public record FileContent(StoredFile file, InputStream content) {}

    /**
     * Stores a file: limit checks → an UPLOADING row → upload into the blob store (computing SHA-256
     * on the fly) → READY. Returns the row with {@code sha256} filled in.
     *
     * <p>A spec that names a session also gets a {@code TOOL} reference: this is the one place every
     * producer passes through, so there is nothing for a new connector to forget. {@code TOOL} is the
     * only kind mintable here — the paths that produce a file without a dispatch (an upload, an
     * ingest) have no session to name, and their context is recorded by the channel funnels instead.
     */
    public StoredFile store(NewFile spec, InputStream content) {
        long sizeBytes = spec.sizeBytes();
        UUID userId = spec.userId();
        if (sizeBytes <= 0) {
            throw new FileRejectedException("file size must be positive, got " + sizeBytes);
        }
        if (sizeBytes > props.getMaxFileSizeBytes()) {
            throw new FileRejectedException("file too large: " + sizeBytes + " bytes, limit "
                    + props.getMaxFileSizeBytes());
        }
        long usedToday = storedFileRepository.sumBytesSince(userId, LocalDateTime.now().minusDays(1));
        if (usedToday + sizeBytes > props.getUserDailyBytes()) {
            throw new FileRejectedException("daily file quota exceeded: " + usedToday + " of "
                    + props.getUserDailyBytes() + " bytes used in the last 24h");
        }

        Duration effectiveTtl = spec.ttl() != null ? spec.ttl() : props.getDefaultTtl();
        StoredFile file = StoredFile.builder()
                .id(UUIDUtils.generateUUIDv8())
                .userId(userId)
                .agentId(spec.agentId())
                .status(FileStatus.UPLOADING)
                .mime(spec.mime())
                .name(spec.name())
                .sizeBytes(sizeBytes)
                .origin(spec.origin())
                .expiresAt(LocalDateTime.now().plus(effectiveTtl))
                .build();
        storedFileRepository.save(file);

        DigestInputStream digest = new DigestInputStream(content, sha256Digest());
        blobStore.put(blobKey(file), digest, sizeBytes, spec.mime());

        file.setSha256(HexFormat.of().formatHex(digest.getMessageDigest().digest()));
        file.setStatus(FileStatus.READY);
        storedFileRepository.save(file);
        // The name is the user's content, like the bytes — it stays out of the log.
        log.info("stored file {} for user {}: origin={}, agent={}, mime={}, {} bytes",
                FileIds.external(file.getId()), userId, spec.origin(), spec.agentId(),
                spec.mime(), sizeBytes);
        if (spec.sessionId() != null) {
            fileReferenceService.record(file.getId(), spec.sessionId(), spec.agentId(),
                    FileReferenceKind.TOOL);
        }
        return file;
    }

    /**
     * Metadata of a file the caller may read: own + READY + not expired. {@code empty} — the id is
     * unknown, foreign, expired or not fully uploaded (the reasons are deliberately indistinguishable).
     */
    public Optional<StoredFile> findReadable(UUID userId, String fileId) {
        return findLive(fileId)
                .filter(f -> f.getUserId().equals(userId));
    }

    /** Opens a file by its public id ({@code agf_<uuid>}) with an ownership check (see {@link #findReadable}). */
    public FileContent open(UUID userId, String fileId) {
        StoredFile file = findReadable(userId, fileId)
                .orElseThrow(() -> new StoredFileNotFoundException(fileId));
        return new FileContent(file, blobStore.get(blobKey(file)));
    }

    /**
     * Opens a file without an ownership check — only for access via a signed link
     * ({@code SignedFileUrlService}): ownership was proven when the link was issued, and the caller
     * has already verified the signature. The other filters (READY, TTL) still apply.
     */
    public FileContent openSigned(String fileId) {
        StoredFile file = findLive(fileId)
                .orElseThrow(() -> new StoredFileNotFoundException(fileId));
        return new FileContent(file, blobStore.get(blobKey(file)));
    }

    /** Row of a file fit for reading: exists + READY + not expired (no ownership check). */
    private Optional<StoredFile> findLive(String fileId) {
        return FileIds.parse(fileId)
                .flatMap(storedFileRepository::findById)
                .filter(f -> f.getStatus() == FileStatus.READY)
                .filter(f -> f.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    /**
     * One cleanup batch: expired READY plus abandoned UPLOADING (the rows come under
     * {@code SKIP LOCKED}, see the repository). The blob is deleted before the row: an S3 delete is
     * idempotent, so a retry after a failure is safe, and no orphaned blobs are left without rows.
     *
     * @return the number of files deleted
     */
    @Transactional
    public int purgeExpiredBatch(int limit) {
        List<StoredFile> batch = storedFileRepository.claimPurgeBatch(limit);
        for (StoredFile file : batch) {
            blobStore.delete(blobKey(file));
            storedFileRepository.delete(file);
        }
        return batch.size();
    }

    private static String blobKey(StoredFile file) {
        return FileLink.of(file).blobKey();
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
