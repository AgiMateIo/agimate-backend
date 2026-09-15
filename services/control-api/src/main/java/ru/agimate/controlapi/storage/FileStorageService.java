package ru.agimate.controlapi.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.util.UUIDUtils;
import ru.agimate.controlapi.config.FileStorageProperties;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.entities.StoredFileVersion;
import ru.agimate.controlapi.database.enums.FileReferenceKind;
import ru.agimate.controlapi.database.enums.FileStatus;
import ru.agimate.controlapi.database.repositories.StoredFileRepository;
import ru.agimate.controlapi.database.repositories.StoredFileVersionRepository;
import ru.agimate.controlapi.service.file.FileReferenceService;

import java.io.ByteArrayInputStream;
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
 * {@link BlobStore} under the key {@link FileLink#blobKey}. Ownership is {@code user_id}: a foreign
 * fileId does not resolve by construction.
 *
 * <p>A file is a document with versions: reading by id returns the current one, and only a caller
 * that recorded what exactly was sent pins a number ({@link #open(UUID, String, int)}).
 *
 * <p>{@link #store} and {@link #storeVersion} are deliberately NOT transactional: each has a network
 * upload to S3 in the middle, and a transaction must not be held across it. A failed upload leaves an
 * UPLOADING row or a claimed attempt — both are harmless and time out on their own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    /** A write attempt silent for this long is presumed dead, and the file can be claimed again. */
    static final Duration CLAIM_STALE_AFTER = Duration.ofMinutes(5);

    private final StoredFileRepository storedFileRepository;
    private final StoredFileVersionRepository storedFileVersionRepository;
    private final BlobStore blobStore;
    private final FileStorageProperties props;
    private final FileReferenceService fileReferenceService;

    /**
     * One version of a file plus a stream of its contents; the caller closes the stream.
     *
     * @param link describes the version read — the current one unless a version was pinned, so its
     *             mime may differ from what the file row says now
     */
    public record FileContent(FileLink link, long size, InputStream content) {

        public String mime() {
            return link.mime();
        }
    }

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
        checkLimits(userId, sizeBytes);

        Duration effectiveTtl = ttl(spec);
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
        FileLink link = FileLink.of(file);
        blobStore.put(link.blobKey(), digest, sizeBytes, FileContentHeaders.forDelivery(link, true));

        file.setSha256(HexFormat.of().formatHex(digest.getMessageDigest().digest()));
        // The journal row goes first: a file READY without its version 1 would refuse every
        // attachment pinned to it once rewritten, while a failure here leaves an UPLOADING row the
        // sweep removes, its version along with it.
        storedFileVersionRepository.save(StoredFileVersion.builder()
                .fileId(file.getId())
                .version(file.getVersion())
                .mime(file.getMime())
                .sizeBytes(sizeBytes)
                .sha256(file.getSha256())
                .agentId(file.getAgentId())
                .origin(file.getOrigin())
                .build());
        file.setStatus(FileStatus.READY);
        storedFileRepository.save(file);
        // The name is the user's content, like the bytes — it stays out of the log.
        log.info("stored file {} for user {}: origin={}, agent={}, mime={}, {} bytes",
                FileIds.external(file.getId()), userId, spec.origin(), spec.agentId(),
                spec.mime(), sizeBytes);
        recordToolReference(file.getId(), spec);
        return file;
    }

    /**
     * Writes new contents of an existing file as its next version. Writes of one file go one at a
     * time: the attempt claims a number first ({@link StoredFileRepository#claimVersion}), uploads
     * under the key that number derives, then commits only if the claim is still its own. A refusal
     * at either end is {@link FileVersionConflictException}, never a retry — two writers racing over
     * one document is rare, and the loser should look at the new text before trying again.
     *
     * <p>Identical contents (same sha256 and mime) make no version; a new name is still written onto
     * the file, since the name belongs to the document. Either way the write extends
     * {@code expires_at} to at least {@code now + ttl}.
     *
     * <p>A failed upload leaves the claim in place: the file refuses writes until
     * {@link #CLAIM_STALE_AFTER}. Releasing it would hand the number out again, while the failed
     * attempt might still finish its upload under that key.
     *
     * @param expectedVersion the version the caller read; anything else is a conflict
     * @param spec            {@code userId} is the ownership check; {@code name} {@code null} keeps
     *                        the current name; {@code sizeBytes} is ignored in favour of the content
     */
    public StoredFile storeVersion(String fileId, int expectedVersion, NewFile spec, byte[] content) {
        UUID userId = spec.userId();
        checkLimits(userId, content.length);
        StoredFile file = findReadable(userId, fileId)
                .orElseThrow(() -> new StoredFileNotFoundException(fileId));
        if (file.getVersion() != expectedVersion) {
            throw new FileVersionConflictException(fileId);
        }
        String sha256 = HexFormat.of().formatHex(sha256Digest().digest(content));
        LocalDateTime now = LocalDateTime.now();

        if (sha256.equals(file.getSha256()) && spec.mime().equals(file.getMime())) {
            if (storedFileRepository.touch(file.getId(), spec.name(), now.plus(ttl(spec)), now) == 0) {
                throw new StoredFileNotFoundException(fileId);
            }
            recordToolReference(file.getId(), spec);
            return reload(file.getId(), fileId);
        }

        Integer claimed = storedFileRepository.claimVersion(file.getId(), expectedVersion,
                now.minus(CLAIM_STALE_AFTER), now);
        if (claimed == null) {
            throw new FileVersionConflictException(fileId);
        }
        FileLink link = new FileLink(userId, fileId, spec.mime(),
                spec.name() != null ? spec.name() : file.getName(), claimed);
        blobStore.put(link.blobKey(), new ByteArrayInputStream(content), content.length,
                FileContentHeaders.forDelivery(link, true));

        int committed = storedFileRepository.commitVersion(file.getId(), claimed, spec.mime(),
                content.length, sha256, spec.agentId(), spec.origin(), spec.name(),
                now.plus(ttl(spec)), LocalDateTime.now());
        if (committed == 0) {
            // Taken over as stale: the key is this attempt's alone, so nobody else's bytes go with it.
            blobStore.delete(link.blobKey());
            throw new FileVersionConflictException(fileId);
        }
        log.info("stored version {} of file {} for user {}: origin={}, agent={}, mime={}, {} bytes",
                claimed, fileId, userId, spec.origin(), spec.agentId(), spec.mime(), content.length);
        recordToolReference(file.getId(), spec);
        return reload(file.getId(), fileId);
    }

    /**
     * Metadata of a file the caller may read: own + READY + not expired. {@code empty} — the id is
     * unknown, foreign, expired or not fully uploaded (the reasons are deliberately indistinguishable).
     */
    public Optional<StoredFile> findReadable(UUID userId, String fileId) {
        return findLive(fileId)
                .filter(f -> f.getUserId().equals(userId));
    }

    /** Opens the current version of a file by its public id ({@code agf_<uuid>}) with an ownership check (see {@link #findReadable}). */
    public FileContent open(UUID userId, String fileId) {
        return open(userId, fileId, 0);
    }

    /**
     * Opens one version of a file with an ownership check. A version the file never had — or an
     * attempt that never committed — is as unavailable as a foreign file.
     *
     * @param version {@code 0} or less — the current one
     */
    public FileContent open(UUID userId, String fileId, int version) {
        StoredFile file = findReadable(userId, fileId)
                .orElseThrow(() -> new StoredFileNotFoundException(fileId));
        return openVersion(file, version);
    }

    /**
     * Opens a version of a file without an ownership check — only for access via a signed link
     * ({@code SignedFileUrlService}): ownership was proven when the link was issued, and the caller
     * has already verified the signature. The other filters (READY, TTL) still apply.
     */
    public FileContent openSigned(String fileId, int version) {
        StoredFile file = findLive(fileId)
                .orElseThrow(() -> new StoredFileNotFoundException(fileId));
        return openVersion(file, version);
    }

    private FileContent openVersion(StoredFile file, int version) {
        FileLink current = FileLink.of(file);
        if (version <= 0 || version == file.getVersion()) {
            return new FileContent(current, file.getSizeBytes(), blobStore.get(current.blobKey()));
        }
        StoredFileVersion pinned = storedFileVersionRepository.findByFileIdAndVersion(file.getId(), version)
                .orElseThrow(() -> new StoredFileNotFoundException(current.fileId()));
        FileLink link = new FileLink(file.getUserId(), current.fileId(), pinned.getMime(), file.getName(),
                version);
        return new FileContent(link, pinned.getSizeBytes(), blobStore.get(link.blobKey()));
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
     * {@code SKIP LOCKED}, see the repository). The blobs are deleted before the row: an S3 delete is
     * idempotent, so a retry after a failure is safe, and no orphaned blobs are left without rows.
     * Every attempt number ever handed out is swept, not just the committed versions — a crashed
     * write leaves its upload under a number with no journal row.
     *
     * @return the number of files deleted
     */
    @Transactional
    public int purgeExpiredBatch(int limit) {
        List<StoredFile> batch = storedFileRepository.claimPurgeBatch(limit);
        for (StoredFile file : batch) {
            String fileId = FileIds.external(file.getId());
            for (int version = file.getClaimedVersion(); version >= FileLink.FIRST_VERSION; version--) {
                blobStore.delete(FileLink.blobKey(file.getUserId(), fileId, version));
            }
            storedFileRepository.delete(file);
        }
        return batch.size();
    }

    private void checkLimits(UUID userId, long sizeBytes) {
        if (sizeBytes <= 0) {
            throw new FileRejectedException("file size must be positive, got " + sizeBytes);
        }
        if (sizeBytes > props.getMaxFileSizeBytes()) {
            throw new FileRejectedException("file too large: " + sizeBytes + " bytes, limit "
                    + props.getMaxFileSizeBytes());
        }
        long usedToday = storedFileVersionRepository.sumBytesSince(userId, LocalDateTime.now().minusDays(1));
        if (usedToday + sizeBytes > props.getUserDailyBytes()) {
            throw new FileRejectedException("daily file quota exceeded: " + usedToday + " of "
                    + props.getUserDailyBytes() + " bytes used in the last 24h");
        }
    }

    private Duration ttl(NewFile spec) {
        return spec.ttl() != null ? spec.ttl() : props.getDefaultTtl();
    }

    private void recordToolReference(UUID fileId, NewFile spec) {
        if (spec.sessionId() != null) {
            fileReferenceService.record(fileId, spec.sessionId(), spec.agentId(), FileReferenceKind.TOOL);
        }
    }

    private StoredFile reload(UUID id, String fileId) {
        return storedFileRepository.findById(id)
                .orElseThrow(() -> new StoredFileNotFoundException(fileId));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
