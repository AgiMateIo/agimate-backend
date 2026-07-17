package ru.agimate.controlapi.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.util.UUIDUtils;
import ru.agimate.controlapi.config.FileStorageProperties;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.enums.FileStatus;
import ru.agimate.controlapi.database.repositories.StoredFileRepository;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Файловый слой коннекторов (docs/connectors/files.md): метаданные в {@code files}, байты в
 * {@link BlobStore} под ключом {@code userId/agf_<id>}. Владение — {@code user_id}: чужой fileId
 * не резолвится по построению.
 *
 * <p>{@link #store} намеренно НЕ транзакционен: между insert'ом UPLOADING-строки и переводом в
 * READY идёт сетевой аплоад в S3, держать транзакцию поверх него нельзя. Упавшая загрузка
 * оставляет UPLOADING-строку — её (и возможный blob-сирота) подметает {@link #purgeExpiredBatch}
 * по возрасту.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final StoredFileRepository storedFileRepository;
    private final BlobStore blobStore;
    private final FileStorageProperties props;

    /** Метаданные + стрим содержимого; стрим закрывает вызывающий. */
    public record FileContent(StoredFile file, InputStream content) {}

    /**
     * Сохраняет файл: проверки лимитов → UPLOADING-строка → аплоад в blob store (с подсчётом
     * SHA-256 на лету) → READY. Возвращает строку с заполненным {@code sha256}.
     *
     * @param ttl null — {@code app.files.default-ttl}
     */
    public StoredFile store(UUID userId, String origin, String mime, long sizeBytes,
                            InputStream content, Duration ttl) {
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

        Duration effectiveTtl = ttl != null ? ttl : props.getDefaultTtl();
        StoredFile file = StoredFile.builder()
                .id(UUIDUtils.generateUUIDv8())
                .userId(userId)
                .status(FileStatus.UPLOADING)
                .mime(mime)
                .sizeBytes(sizeBytes)
                .origin(origin)
                .expiresAt(LocalDateTime.now().plus(effectiveTtl))
                .build();
        storedFileRepository.save(file);

        DigestInputStream digest = new DigestInputStream(content, sha256Digest());
        blobStore.put(blobKey(file), digest, sizeBytes, mime);

        file.setSha256(HexFormat.of().formatHex(digest.getMessageDigest().digest()));
        file.setStatus(FileStatus.READY);
        storedFileRepository.save(file);
        log.info("stored file {} for user {}: origin={}, mime={}, {} bytes",
                FileIds.external(file.getId()), userId, origin, mime, sizeBytes);
        return file;
    }

    /**
     * Открывает файл по публичному id ({@code agf_<uuid>}) с проверкой владения.
     * Неизвестный/чужой/непросроченный-но-не-READY id неразличимы для вызывающего.
     */
    public FileContent open(UUID userId, String fileId) {
        StoredFile file = FileIds.parse(fileId)
                .flatMap(storedFileRepository::findById)
                .filter(f -> f.getUserId().equals(userId))
                .filter(f -> f.getStatus() == FileStatus.READY)
                .filter(f -> f.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new StoredFileNotFoundException(fileId));
        return new FileContent(file, blobStore.get(blobKey(file)));
    }

    /**
     * Один батч чистки: просроченные READY + брошенные UPLOADING (строки — под
     * {@code SKIP LOCKED}, см. репозиторий). Блоб удаляется до строки: S3-delete идемпотентен,
     * повтор после сбоя безопасен, а сирот-блобов без строк не остаётся.
     *
     * @return число удалённых файлов
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
        return file.getUserId() + "/" + FileIds.external(file.getId());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
