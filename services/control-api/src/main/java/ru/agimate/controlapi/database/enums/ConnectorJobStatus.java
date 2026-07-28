package ru.agimate.controlapi.database.enums;

/**
 * State of a {@code connector_jobs} row in the pull-based scheduler.
 * <ul>
 *   <li>{@link #PENDING} — queued, waiting for its {@code next_run_at}.</li>
 *   <li>{@link #RUNNING} — the current node has claimed the row and is executing it.
 *       {@code lease_until} is how long the lease is considered alive; past that the row counts as
 *       stuck and is picked up again (crash recovery).</li>
 *   <li>{@link #COMPLETED} — a one-shot ({@code ONETIME}) job finished successfully; the row is no
 *       longer picked up. An upsert by the business key returns it to {@code PENDING}.</li>
 * </ul>
 */
public enum ConnectorJobStatus {
    PENDING,
    RUNNING,
    COMPLETED
}
