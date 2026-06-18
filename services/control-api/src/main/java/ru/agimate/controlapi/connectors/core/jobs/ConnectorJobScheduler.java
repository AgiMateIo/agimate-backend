package ru.agimate.controlapi.connectors.core.jobs;

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
import java.util.concurrent.ThreadFactory;

/**
 * Pull‑based scheduler фоновых задач коннекторов.
 *
 * <p>На каждом тике (раз в секунду) атомарно claim'ит готовые к запуску строки
 * {@code connector_jobs} через {@code FOR UPDATE SKIP LOCKED}, сразу переводя их в
 * {@code RUNNING} с per-row lease ({@code now + timeout_seconds}). Для каждой claim'нутой строки
 * сабмитит виртуальный поток, который:
 *
 * <ol>
 *   <li>вызывает {@link JobExecutionService#executeJob(ConnectorJob)} вне транзакции
 *       (важно: long‑poll может держать поток 20с, коннект к БД на это время отдан в пул);</li>
 *   <li>обновляет {@code next_run_at} и переводит строку обратно в {@code PENDING},
 *       либо в {@code COMPLETED} для успешного {@code ONETIME}.</li>
 * </ol>
 *
 * <p>Если процесс упал между claim и complete — lease истечёт сам, и любая нода (включая эту
 * после рестарта) повторно подхватит строку. Никакого in‑memory tracking нет.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorJobScheduler {

    /** Сколько строк подхватываем за один тик. С запасом — на проде задач должны быть единицы. */
    private static final int BATCH_SIZE = 100;

    /** Дефолтная задержка повтора после ошибки. */
    private static final Duration DEFAULT_ERROR_RETRY = Duration.ofSeconds(60);

    private final ConnectorJobService jobService;
    private final JobExecutionService jobExecutionService;

    private final ThreadFactory virtualThreads = Thread.ofVirtual().name("cjob-", 0).factory();

    @Scheduled(fixedDelay = 1_000)
    public void tick() {
        List<ConnectorJob> claimed = jobService.claimReady(BATCH_SIZE);
        if (claimed.isEmpty()) {
            return;
        }
        log.debug("Claimed {} task(s)", claimed.size());
        for (ConnectorJob row : claimed) {
            virtualThreads.newThread(() -> execute(row)).start();
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
                log.error("Job {} failed: {}", jobKey, e.toString(), e);
                jobService.complete(row.getId(), computeNext(row, true), summarize(e));
            }
        }
    }

    /**
     * Когда задача должна запуститься в следующий раз.
     * <ul>
     *   <li>ONETIME: сюда попадает только после ошибки — retry через {@code DEFAULT_ERROR_RETRY},
     *       успех финализируется {@code markCompleted} без следующего запуска.</li>
     *   <li>PERIODIC: {@code now + intervalSeconds} в норме; {@code now + DEFAULT_ERROR_RETRY} при ошибке.</li>
     *   <li>CRON: следующий cron‑тик (ошибка тоже идёт в очередь по cron — пропускаем итерацию).</li>
     * </ul>
     */
    private LocalDateTime computeNext(ConnectorJob row, boolean afterError) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> config = row.getConfig() == null ? Map.of() : row.getConfig();
        return switch (row.getType()) {
            case ONETIME -> now.plus(DEFAULT_ERROR_RETRY);
            case PERIODIC -> afterError
                    ? now.plus(DEFAULT_ERROR_RETRY)
                    : now.plusSeconds(JobSchedule.readLong(config, "intervalSeconds", 0L));
            case CRON -> JobSchedule.nextCron(config, now);
        };
    }

    private static String jobKey(ConnectorJob row) {
        return row.getConnectorCode() + "/"
                + (row.getIdentity() == null ? "global" : row.getIdentity()) + "/"
                + row.getName();
    }

    private static String summarize(Throwable e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
}
