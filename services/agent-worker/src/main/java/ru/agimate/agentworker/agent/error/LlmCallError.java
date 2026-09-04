package ru.agimate.agentworker.agent.error;

/**
 * Raised by the dispatcher when the model request reports an HTTP/API error (returned as a
 * failure value, not an exception, so DBOS never logs it at ERROR).
 * {@link ru.agimate.agentworker.workers.run.AgentRunCore} maps
 * this to an {@link AgentRunAborted}. A {@code null} {@code statusCode} means a non-HTTP API error.
 */
public class LlmCallError extends RuntimeException {
    private final Integer statusCode;
    private final boolean userFacing;

    public LlmCallError(Integer statusCode, String message) {
        this(statusCode, message, false);
    }

    public LlmCallError(Integer statusCode, String message, boolean userFacing) {
        super(message);
        this.statusCode = statusCode;
        this.userFacing = userFacing;
    }

    public Integer statusCode() {
        return statusCode;
    }

    /** {@code message} is a server-authored user notice (e.g. a quota message) — surface verbatim. */
    public boolean userFacing() {
        return userFacing;
    }
}
