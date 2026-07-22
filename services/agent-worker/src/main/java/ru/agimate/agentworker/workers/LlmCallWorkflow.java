package ru.agimate.agentworker.workers;

import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.ToolDef;

import java.util.List;

/** One model request per {@code llm_calls} queue item, so model traffic gets its own concurrency. */
public interface LlmCallWorkflow {

    Result llmCall(List<AgentChatMessage> messages, List<ToolDef> toolDefs, String agentId);

    /**
     * A failure is returned (not thrown) so DBOS never logs the HTTP/API error at ERROR with a
     * stack trace; the dispatcher converts a failure back into an exception in plain context.
     * {@code statusCode} is null for a non-HTTP API error. {@code userFacing} means {@code message}
     * is a server-authored notice for the user (e.g. a quota message) and must be surfaced verbatim
     * rather than mapped to a generic notice. {@code finishReason} is the provider's raw
     * {@code finish_reason} on a successful call (null on failures) — the dispatcher decides which
     * reasons are terminal (truncation/filtering).
     */
    record Result(AgentChatMessage assistant, boolean failed, Integer statusCode, String message,
                  boolean userFacing, String finishReason) {

        public static Result ok(AgentChatMessage assistant, String finishReason) {
            return new Result(assistant, false, null, null, false, finishReason);
        }

        public static Result failure(Integer statusCode, String message) {
            return new Result(null, true, statusCode, message, false, null);
        }

        /** Failure whose {@code message} is already user-facing (surfaced verbatim to the channel). */
        public static Result userError(String message) {
            return new Result(null, true, null, message, true, null);
        }
    }
}
