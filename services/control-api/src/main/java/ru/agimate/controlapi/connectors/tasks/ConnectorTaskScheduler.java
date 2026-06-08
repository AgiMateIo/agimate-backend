package ru.agimate.controlapi.connectors.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.entities.ConnectorTask;
import ru.agimate.controlapi.database.enums.ConnectorTaskType;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadFactory;

/**
 * Pull‑based scheduler фоновых задач коннекторов.
 *
 * <p>На каждом тике (раз в секунду) атомарно claim'ит готовые к запуску строки
 * {@code connector_tasks} через {@code FOR UPDATE SKIP LOCKED}, сразу переводя их в
 * {@code RUNNING} с lease. Для каждой claim'нутой строки сабмитит виртуальный поток, который:
 *
 * <ol>
 *   <li>находит исполняемый {@link Task} через {@link TaskResolver};</li>
 *   <li>вызывает {@code task.run()} вне транзакции (важно: long‑poll может держать поток 20с,
 *       коннект к БД на это время отдан в пул);</li>
 *   <li>обновляет {@code next_run_at} и переводит строку обратно в {@code PENDING}.</li>
 * </ol>
 *
 * <p>Если процесс упал между claim и complete — lease истечёт сам, и любая нода (включая эту
 * после рестарта) повторно подхватит строку. Никакого in‑memory tracking нет.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorTaskScheduler {

    /** Сколько строк подхватываем за один тик. С запасом — на проде задач должны быть единицы. */
    private static final int BATCH_SIZE = 100;

    /** Default lease: 5 минут покрывает Telegram long‑poll (20с) и среднюю Periodic/Cron задачу. */
    private static final Duration DEFAULT_LEASE = Duration.ofMinutes(5);

    /** Дефолтная задержка повтора после ошибки, если в config не указано. */
    private static final Duration DEFAULT_ERROR_RETRY = Duration.ofSeconds(60);

    private final ConnectorTaskService taskService;
    private final List<TaskResolver> resolvers;

    private final ThreadFactory virtualThreads = Thread.ofVirtual().name("ctask-", 0).factory();

    @Scheduled(fixedDelay = 1_000)
    public void tick() {
        List<ConnectorTask> claimed = taskService.claimReady(DEFAULT_LEASE, BATCH_SIZE);
        if (claimed.isEmpty()) {
            return;
        }
        log.debug("Claimed {} task(s)", claimed.size());
        for (ConnectorTask row : claimed) {
            virtualThreads.newThread(() -> execute(row)).start();
        }
    }

    private void execute(ConnectorTask row) {
        TaskKey key = toKey(row);
        try (MDC.MDCCloseable __ = MDC.putCloseable("taskKey", key.asString())) {
            Optional<Task> resolved = resolve(row);
            if (resolved.isEmpty()) {
                log.warn("No TaskResolver for {} — completing with error", key);
                taskService.complete(row.getId(), computeNext(row, true), "No TaskResolver");
                return;
            }
            try {
                resolved.get().run();
                taskService.complete(row.getId(), computeNext(row, false), null);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Task {} interrupted", key);
                taskService.complete(row.getId(), computeNext(row, true), "Interrupted");
            } catch (Exception e) {
                log.error("Task {} failed: {}", key, e.toString(), e);
                taskService.complete(row.getId(), computeNext(row, true), summarize(e));
            }
        }
    }

    /**
     * Когда задача должна запуститься в следующий раз.
     * <ul>
     *   <li>PERIODIC: {@code now + intervalSeconds} в норме; {@code now + DEFAULT_ERROR_RETRY} при ошибке.</li>
     *   <li>CRON: следующий cron‑тик (ошибка тоже идёт в очередь по cron — пропускаем итерацию).</li>
     * </ul>
     */
    private LocalDateTime computeNext(ConnectorTask row, boolean afterError) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> config = row.getConfig() == null ? Map.of() : row.getConfig();
        return switch (row.getTaskType()) {
            case PERIODIC -> afterError
                    ? now.plus(DEFAULT_ERROR_RETRY)
                    : now.plusSeconds(readLong(config, "intervalSeconds", 0L));
            case CRON -> nextCron(config, now);
        };
    }

    private static LocalDateTime nextCron(Map<String, Object> config, LocalDateTime now) {
        String expr = (String) config.get("cron");
        if (expr == null || expr.isBlank()) {
            // Без выражения в конфиге cron не запустится — отодвигаем далеко, чтобы не ловить
            // SKIP LOCKED'ом на каждом тике.
            return now.plusYears(10);
        }
        String zoneId = (String) config.getOrDefault("zone", "UTC");
        CronExpression cron = CronExpression.parse(expr);
        LocalDateTime next = cron.next(now.atZone(ZoneId.of(zoneId))).toLocalDateTime();
        return next != null ? next : now.plusYears(10);
    }

    private static long readLong(Map<String, Object> config, String key, long defaultValue) {
        return config.get(key) instanceof Number n ? n.longValue() : defaultValue;
    }

    private Optional<Task> resolve(ConnectorTask row) {
        for (TaskResolver resolver : resolvers) {
            Optional<Task> result = resolver.resolve(row);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    private static TaskKey toKey(ConnectorTask row) {
        TaskScope scope = switch (row.getScopeKind()) {
            case GLOBAL -> TaskScope.global();
            case INTEGRATION -> TaskScope.integration(row.getScopeId());
            case USER -> TaskScope.user(row.getScopeId());
        };
        return new TaskKey(row.getConnectorCode(), scope, row.getTaskCode());
    }

    private static String summarize(Throwable e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
}
