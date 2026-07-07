package ru.agimate.agentworker.agent;

/**
 * Raised by the dispatcher when the LLM workflow reports an HTTP/API error (returned as a
 * failure value, not an exception, so DBOS never logs it at ERROR). {@link AgentRunner} maps
 * this to an {@link AgentRunAborted}. A {@code null} {@code statusCode} means a non-HTTP API error.
 */
public class LlmCallError extends RuntimeException {
    private final Integer statusCode;

    public LlmCallError(Integer statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public Integer statusCode() {
        return statusCode;
    }
}
