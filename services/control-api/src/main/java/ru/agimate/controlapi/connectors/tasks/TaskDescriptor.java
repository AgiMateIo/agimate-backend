package ru.agimate.controlapi.connectors.tasks;

import java.time.Duration;
import java.time.ZoneId;

/**
 * Описание задачи, которое handler возвращает из {@code getBackgroundTasks(...)}.
 * Чисто данные — менеджер транслирует их в строки {@code connector_tasks}, а шедулер по описанию
 * выбирает подходящий backend ({@link LongRunning} / {@link Periodic} / {@link Cron}).
 *
 * <p>Для {@code IntegrationHandler.getBackgroundTasks(creds)} поле {@link #scope()} может быть
 * любым — listener перезаписывает его на {@code TaskScope.integration(creds.getId())} перед
 * upsert'ом, поэтому handler может ставить {@code TaskScope.global()} как placeholder.
 */
public sealed interface TaskDescriptor {

    TaskScope scope();

    String taskCode();

    Task task();

    /** Долгоживущий цикл (long‑polling, websocket reconnect и т.п.). */
    record LongRunning(TaskScope scope, String taskCode, Task task, BackoffPolicy onError)
            implements TaskDescriptor {

        public LongRunning {
            if (onError == null) {
                onError = BackoffPolicy.DEFAULT;
            }
        }
    }

    /** Периодический запуск с фиксированным интервалом. */
    record Periodic(TaskScope scope, String taskCode, Task task, Duration interval, Duration initialDelay)
            implements TaskDescriptor {

        public Periodic {
            if (interval == null || interval.isZero() || interval.isNegative()) {
                throw new IllegalArgumentException("Periodic.interval must be positive");
            }
            if (initialDelay == null) {
                initialDelay = Duration.ZERO;
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
