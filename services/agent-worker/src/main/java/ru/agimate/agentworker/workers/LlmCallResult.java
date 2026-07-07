package ru.agimate.agentworker.workers;

import ru.agimate.agentworker.agent.AgentChatMessage;

/**
 * Result of one {@code llm_call} workflow. A failure is returned (not thrown) so DBOS never logs
 * the HTTP/API error at ERROR with a stack trace; the dispatcher converts a failure back into an
 * exception in plain context. {@code statusCode} is null for a non-HTTP API error.
 */
public record LlmCallResult(AgentChatMessage assistant, boolean failed, Integer statusCode, String message) {

    public static LlmCallResult ok(AgentChatMessage assistant) {
        return new LlmCallResult(assistant, false, null, null);
    }

    public static LlmCallResult failure(Integer statusCode, String message) {
        return new LlmCallResult(null, true, statusCode, message);
    }
}
