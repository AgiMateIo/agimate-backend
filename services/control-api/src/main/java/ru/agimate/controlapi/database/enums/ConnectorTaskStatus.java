package ru.agimate.controlapi.database.enums;

/**
 * Состояние строки {@code connector_tasks} в pull‑based scheduler'е.
 * <ul>
 *   <li>{@link #PENDING} — задача в очереди, ждёт момента {@code next_run_at}.</li>
 *   <li>{@link #RUNNING} — текущая нода claim'нула строку и выполняет её. {@code lease_until}
 *       — до какого момента lease считается живым; по истечении строка считается «зависшей»
 *       и подхватывается повторно (crash‑recovery).</li>
 * </ul>
 */
public enum ConnectorTaskStatus {
    PENDING,
    RUNNING
}
