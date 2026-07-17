package ru.agimate.controlapi.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Периодическая чистка файлового слоя: просроченные READY + брошенные UPLOADING.
 * Батчи под {@code SKIP LOCKED} — несколько инстансов не мешают друг другу.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileCleanupTask {

    private static final int BATCH_SIZE = 100;

    private final FileStorageService fileStorageService;

    @Scheduled(fixedDelay = 60_000)
    public void purgeExpired() {
        int total = 0;
        try {
            int purged;
            do {
                purged = fileStorageService.purgeExpiredBatch(BATCH_SIZE);
                total += purged;
            } while (purged == BATCH_SIZE);
        } catch (Exception e) {
            // Недоступный blob store не должен ронять scheduler — попробуем на следующем тике.
            log.warn("file cleanup pass failed after {} purged: {}", total, e.getMessage());
            return;
        }
        if (total > 0) {
            log.info("purged {} expired file(s)", total);
        }
    }
}
