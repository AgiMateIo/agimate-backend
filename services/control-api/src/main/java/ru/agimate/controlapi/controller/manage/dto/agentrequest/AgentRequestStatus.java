package ru.agimate.controlapi.controller.manage.dto.agentrequest;

/**
 * State of a request, folded from the runs of its thread — no column of its own, so it cannot go
 * stale. A stopped run wins over a live one: a run cancelled mid-flight is still running for a
 * moment, and the user who stopped it is not told it is working.
 */
public enum AgentRequestStatus {
    /** A live run of the thread, or a thread just created whose run is still being routed. */
    WORKING,
    /** The callee answered. */
    DONE,
    /** The callee's run ended with an error, or went silent and was swept. */
    FAILED,
    /** Stopped: the conversation was cancelled, and the thread with it. */
    CANCELLED,
    /**
     * Asked, but nothing ran: the request never made it into the queue, or a run has been sitting
     * ENQUEUED past the window in which a queued run is believed alive. Nothing sweeps that, so the
     * listing says so instead of showing work that is not happening.
     */
    STALLED
}
