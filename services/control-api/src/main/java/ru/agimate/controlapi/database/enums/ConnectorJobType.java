package ru.agimate.controlapi.database.enums;

/**
 * Job type — decides how the scheduler computes {@code next_run_at} once an iteration finishes.
 * <ul>
 *   <li>{@link #ONETIME} — one-shot: after a successful run the row moves to {@code COMPLETED} and
 *       is never picked up again; on failure it is retried through error retry.</li>
 *   <li>{@link #PERIODIC} — a fixed interval from {@code config.intervalSeconds}.</li>
 *   <li>{@link #CRON} — the next tick of the cron expression from {@code config.cron}/{@code config.zone}.</li>
 * </ul>
 */
public enum ConnectorJobType {
    ONETIME,
    PERIODIC,
    CRON
}
