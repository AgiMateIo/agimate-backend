package ru.agimate.deviceapi.database.entities;

/**
 * Lifecycle of an agent run (a {@link TriggerLogAgent} row).
 * <p>
 * Orthogonal to {@code result}/{@code error} (which capture the run outcome):
 * {@code status} captures liveness for the active-run registry (AgentRunRegistry).
 * The single-writer-per-session invariant is enforced on {@link #RUNNING}.
 */
public enum RunStatus {
    /** Created by the backend at trigger routing, enqueued to the worker, not yet writing. */
    ENQUEUED,
    /** The worker has acquired the session slot and is the active writer. */
    RUNNING,
    /** Finished normally (released by the run). */
    DONE,
    /** Delivery/enqueue failed before or during the run. */
    FAILED,
    /** Pre-empted by an INTERRUPT take-over before completing. */
    CANCELLED
}
