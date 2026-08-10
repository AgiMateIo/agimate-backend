package ru.agimate.controlapi.database.enums;

import ru.agimate.controlapi.database.entities.AgentRun;

/**
 * Lifecycle of an agent run (a {@link AgentRun} row) — a projection of the run's
 * {@code SaveMessage} stream (INBOUND → RUNNING, ANSWER → DONE, ERROR → FAILED), observability
 * only. Single-writer-per-session is enforced by the partitioned {@code agent_exec} queue,
 * not by this status.
 */
public enum RunStatus {
    /** Created by the backend at trigger routing, enqueued to the worker, not yet executing. */
    ENQUEUED,
    /** The run has started executing (first SaveMessage arrived). */
    RUNNING,
    /** Finished normally (final ANSWER). */
    DONE,
    /** Reported an ERROR, or went silent and was swept as stale. */
    FAILED,
    /**
     * Stopped at the user's request. Reached from a terminal record that arrives with
     * {@code cancel_requested_at} already set, or from the stale sweeper — a run whose worker died
     * after the request is better explained by the user's intent than by «went silent».
     */
    CANCELLED
}
