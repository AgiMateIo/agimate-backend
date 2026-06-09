package ru.agimate.controlapi.database.enums;

/**
 * Тип задачи — определяет, как scheduler вычисляет {@code next_run_at} после завершения итерации.
 * <ul>
 *   <li>{@link #ONETIME} — одноразовая: после успешного запуска строка переводится в
 *       {@code COMPLETED} и больше не подхватывается; при ошибке ретраится через error retry.</li>
 *   <li>{@link #PERIODIC} — фиксированный интервал из {@code task_config.intervalSeconds}.</li>
 *   <li>{@link #CRON} — следующий тик cron‑выражения из {@code task_config.cron}/{@code task_config.zone}.</li>
 * </ul>
 */
public enum ConnectorTaskType {
    ONETIME,
    PERIODIC,
    CRON
}
