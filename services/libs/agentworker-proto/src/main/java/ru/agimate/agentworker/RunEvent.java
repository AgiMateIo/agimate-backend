package ru.agimate.agentworker;

/**
 * An event control-api sends to a running workflow over the DBOS message table, so the workflow
 * wakes on a fact instead of a poll. A plain record on purpose: it becomes a JSON row of the DBOS
 * system database, never a protobuf message and never a secret.
 *
 * <p>{@code runId} is carried only where it means something to the receiver — the new run that
 * arrived in the session; the destination workflow id is the run being notified.
 */
public record RunEvent(String kind, String toolCallId, String runId) {

    public static final String TOOL_FINISHED = "tool_finished";
    public static final String STEERABLE = "steerable";
    public static final String CANCEL_REQUESTED = "cancel_requested";

    /** A tool call of the run finished (or was detached): its result can be read now. */
    public static RunEvent toolFinished(String toolCallId) {
        return new RunEvent(TOOL_FINISHED, toolCallId, null);
    }

    /** A new run appeared in the session — a message, an event, a report: there is something to absorb. */
    public static RunEvent steerable(String runId) {
        return new RunEvent(STEERABLE, null, runId);
    }

    /** The user asked to stop the run: a waiting tool step can end now instead of blocking. */
    public static RunEvent cancelRequested() {
        return new RunEvent(CANCEL_REQUESTED, null, null);
    }
}
