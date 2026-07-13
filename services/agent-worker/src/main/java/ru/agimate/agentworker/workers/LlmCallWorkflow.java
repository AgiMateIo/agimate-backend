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
     * rather than mapped to a generic notice.
     */
    record Result(AgentChatMessage assistant, boolean failed, Integer statusCode, String message,
                  boolean userFacing) {

        public static Result ok(AgentChatMessage assistant) {
            return new Result(assistant, false, null, null, false);
        }

        public static Result failure(Integer statusCode, String message) {
            return new Result(null, true, statusCode, message, false);
        }

        /** Failure whose {@code message} is already user-facing (surfaced verbatim to the channel). */
        public static Result userError(String message) {
            return new Result(null, true, null, message, true);
        }
    }
}
