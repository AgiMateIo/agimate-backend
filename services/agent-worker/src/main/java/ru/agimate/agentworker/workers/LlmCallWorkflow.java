package ru.agimate.agentworker.workers;

import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.LlmUsage;
import ru.agimate.agentworker.agent.model.ToolDef;

import java.util.List;

/** One model request per {@code llm_calls} queue item, so model traffic gets its own concurrency. */
public interface LlmCallWorkflow {

    Result llmCall(List<AgentChatMessage> messages, List<ToolDef> toolDefs, String agentId);

    /**
     * A failure is returned (not thrown) so DBOS never logs the HTTP/API error at ERROR with a
     * stack trace; the dispatcher converts a failure back into an exception in plain context.
     * {@code statusCode} is null for a non-HTTP API error. {@code userFacing} means {@code message}
     * is already a notice for the user (a quota message from the server, the «no model configured»
     * notice) and must be surfaced verbatim rather than mapped to a generic one.
     * {@code finishReason} is the provider's raw
     * {@code finish_reason} on a successful call (null on failures) — the dispatcher decides which
     * reasons are terminal (truncation/filtering). {@code model}/{@code callId} carry the turn's
     * provenance to the ledger ({@code callId} = this call's own workflow id, the join key to
     * {@code llm_usage_log}); both null on failures. {@code usage} carries the call's token counts
     * for accounting ({@code null} when there is nothing to account) — the child only
     * <b>returns</b> them; the loop surfaces them and the run wiring reports them (the parent is
     * the sole writer of backend side-records, like {@code SaveMessage}/{@code SaveTurn}), so the
     * child needs no {@code runId}. Adding fields here changes the checkpointed child-workflow
     * result — deploy behind a drain.
     */
    record Result(AgentChatMessage assistant, boolean failed, Integer statusCode, String message,
                  boolean userFacing, String finishReason, String model, String callId, LlmUsage usage) {

        public static Result ok(AgentChatMessage assistant, String finishReason, String model,
                                String callId, LlmUsage usage) {
            return new Result(assistant, false, null, null, false, finishReason, model, callId, usage);
        }

        public static Result failure(Integer statusCode, String message) {
            return new Result(null, true, statusCode, message, false, null, null, null, null);
        }

        /** Failure whose {@code message} is already user-facing (surfaced verbatim to the channel). */
        public static Result userError(String message) {
            return new Result(null, true, null, message, true, null, null, null, null);
        }
    }
}
