package ru.agimate.controlapi.connectors.core.jobs;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.entities.ConnectorJob;
import ru.agimate.controlapi.database.enums.ConnectorJobType;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;

/**
 * Pull-based scheduler of connectors' background jobs.
 *
 * <p>On every tick (once a second) it atomically claims the {@code connector_jobs} rows ready to run,
 * through {@code FOR UPDATE SKIP LOCKED}, moving them straight to {@code RUNNING} with a per-row
 * lease ({@code now + timeout_seconds}). For each claimed row it submits a virtual thread that:
 *
 * <ol>
 *   <li>calls {@link JobExecutionService#executeJob(ConnectorJob)} outside a transaction (this
 *       matters: a long poll can hold the thread for 20s, and the database connection is returned to
 *       the pool for that time);</li>
 *   <li>updates {@code next_run_at} and moves the row back to {@code PENDING}, or to
 *       {@code COMPLETED} for a successful {@code ONETIME}.</li>
 * </ol>
 *
 * <p>If the process dies between claim and complete, the lease expires on its own and any node
 * (including this one after a restart) picks the row up again. There is no in-memory tracking at all.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorJobScheduler {

    /** How many rows we pick up per tick. Generous — in production there should be only a handful of jobs. */
    private static final int BATCH_SIZE = 100;

    /** Default retry delay after a failure. */
    private static final Duration DEFAULT_ERROR_RETRY = Duration.ofSeconds(60);

    private final ConnectorJobService jobService;
    private final JobExecutionService jobExecutionService;

    private final ThreadFactory virtualThreads = Thread.ofVirtual().name("cjob-", 0).factory();

    /** Rows claimed by this node and not yet finished — candidates for release at shutdown. */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    private volatile boolean shuttingDown;

    @Scheduled(fixedDelay = 1_000)
    public void tick() {
        if (shuttingDown) {
            return;
        }
        List<ConnectorJob> claimed = jobService.claimReady(BATCH_SIZE);
        if (claimed.isEmpty()) {
            return;
        }
        log.debug("Claimed {} task(s)", claimed.size());
        for (ConnectorJob row : claimed) {
            inFlight.add(row.getId());
            virtualThreads.newThread(() -> execute(row)).start();
        }
    }

    /**
     * Returns unfinished iterations to the queue before the JVM stops. Without this the row stays
     * RUNNING until its lease expires (or goes into error retry because the pools are closing), and
     * after a restart the job stays silent for timeout_seconds — for a continuous long poll that is a
     * minute of deafness.
     */
    @PreDestroy
    void releaseInFlight() {
        shuttingDown = true;
        for (UUID id : Set.copyOf(inFlight)) {
            try {
                jobService.release(id);
            } catch (Exception e) {
                log.warn("Failed to release job {} on shutdown: {}", id, e.getMessage());
            }
        }
    }

    private void execute(ConnectorJob row) {
        String jobKey = jobKey(row);
        try (MDC.MDCCloseable __ = MDC.putCloseable("jobKey", jobKey)) {
            try {
                jobExecutionService.executeJob(row);
                if (row.getType() == ConnectorJobType.ONETIME) {
                    jobService.markCompleted(row.getId(), null);
                } else {
                    jobService.complete(row.getId(), computeNext(row, false), null);
                }
            } catch (Exception e) {
                if (shuttingDown) {
                    // The iteration was killed by the shutdown itself (the pools closing) — that is not a job
                    // failure, and an error retry would push it a minute past the restart.
                    log.info("Job {} interrupted by shutdown, released", jobKey);
                    jobService.release(row.getId());
                } else {
                    log.error("Job {} failed: {}", jobKey, e.toString(), e);
                    jobService.complete(row.getId(), computeNext(row, true), summarize(e));
                }
            } finally {
                inFlight.remove(row.getId());
            }
        }
    }

    /**
     * When the job should run next.
     * <ul>
     *   <li>ONETIME: reached here only after a failure — retry through {@code DEFAULT_ERROR_RETRY};
     *       success is finalised by {@code markCompleted} with no next run.</li>
     *   <li>PERIODIC: {@code now + intervalSeconds} normally; {@code now + DEFAULT_ERROR_RETRY} on failure.</li>
     *   <li>CRON: the next cron tick (a failure also queues by cron — we skip that iteration).</li>
     * </ul>
     */
    private LocalDateTime computeNext(ConnectorJob row, boolean afterError) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> config = row.getConfig() == null ? Map.of() : row.getConfig();
        return switch (row.getType()) {
            case ONETIME -> now.plus(DEFAULT_ERROR_RETRY);
            case PERIODIC -> afterError
                    ? now.plus(DEFAULT_ERROR_RETRY)
                    : now.plusSeconds(JobSchedule.readLong(config, JobSchedule.KEY_INTERVAL_SECONDS, 0L));
            case CRON -> JobSchedule.nextCron(config, now);
        };
    }

    private static String jobKey(ConnectorJob row) {
        return row.getConnectorCode() + "/"
                + (row.getConnectionId() == null ? "global" : row.getConnectionId()) + "/"
                + row.getName();
    }

    private static String summarize(Throwable e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
}
