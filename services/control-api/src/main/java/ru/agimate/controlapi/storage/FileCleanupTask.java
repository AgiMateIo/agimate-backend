package ru.agimate.controlapi.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic cleanup of the file layer: expired READY plus abandoned UPLOADING. Batches run under
 * {@code SKIP LOCKED} so several instances do not get in each other's way.
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
            // An unreachable blob store must not bring the scheduler down — we will try again on the next tick.
            log.warn("file cleanup pass failed after {} purged: {}", total, e.getMessage());
            return;
        }
        if (total > 0) {
            log.info("purged {} expired file(s)", total);
        }
    }
}
