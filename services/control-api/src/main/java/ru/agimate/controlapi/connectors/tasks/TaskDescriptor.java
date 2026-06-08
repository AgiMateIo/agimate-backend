package ru.agimate.controlapi.connectors.tasks;

import java.time.Duration;
import java.time.ZoneId;

/**
 * Описание задачи, которое handler возвращает из {@code getBackgroundTasks(...)} — listener
 * транслирует его в строку {@code connector_tasks}.
 *
 * <p>После вставки строка живёт своей жизнью: scheduler читает её на каждом тике, дёргает
 * {@link Task} через {@code TaskResolver} и обновляет {@code next_run_at}/{@code last_started_at}.
 * Сам дескриптор в runtime не участвует.
 *
 * <p>Для {@code IntegrationHandler.getBackgroundTasks(creds)} поле {@link #scope()} может быть
 * любым — listener перезаписывает его на {@code TaskScope.integration(creds.getId())} перед
 * upsert'ом, поэтому handler может ставить {@code TaskScope.global()} как placeholder.
 */
public sealed interface TaskDescriptor {

    TaskScope scope();

    String taskCode();

    Task task();

    /**
     * Периодический запуск с фиксированным интервалом. {@code interval=Duration.ZERO} означает
     * «немедленный повтор после завершения» — подходит для long‑poll‑паттернов (Telegram getUpdates).
     *
     * <p>На ошибке scheduler ставит {@code next_run_at = now + 60s} (константа). Если когда‑то
     * понадобится per‑task delay — добавим сюда поле {@code errorRetryDelay} и поле в JSON config.
     */
    record Periodic(TaskScope scope, String taskCode, Task task, Duration interval)
            implements TaskDescriptor {

        public Periodic {
            if (interval == null || interval.isNegative()) {
                throw new IllegalArgumentException("Periodic.interval must be non-negative");
            }
        }
    }

    /** Запуск по cron‑выражению (формат Spring {@code CronExpression}, 6 полей с секундами). */
    record Cron(TaskScope scope, String taskCode, Task task, String cronExpression, ZoneId zone)
            implements TaskDescriptor {

        public Cron {
            if (cronExpression == null || cronExpression.isBlank()) {
                throw new IllegalArgumentException("Cron.cronExpression must be set");
            }
            if (zone == null) {
                zone = ZoneId.of("UTC");
            }
        }
    }
}
