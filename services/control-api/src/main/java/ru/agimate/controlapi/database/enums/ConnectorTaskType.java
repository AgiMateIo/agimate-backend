package ru.agimate.controlapi.database.enums;

/**
 * Тип задачи — определяет, как scheduler вычисляет {@code next_run_at} после завершения итерации.
 * <ul>
 *   <li>{@link #PERIODIC} — фиксированный интервал из {@code config.intervalSeconds}.</li>
 *   <li>{@link #CRON} — следующий тик cron‑выражения из {@code config.cron}/{@code config.zone}.</li>
 * </ul>
 */
public enum ConnectorTaskType {
    PERIODIC,
    CRON
}
