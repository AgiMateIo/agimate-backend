package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.StartWorkflowOptions;
import dev.dbos.transact.workflow.Queue;
import dev.dbos.transact.workflow.WorkflowHandle;
import ru.agimate.agentworker.agent.SimpleAgent;
import ru.agimate.agentworker.agent.error.LlmCallError;
import ru.agimate.agentworker.agent.error.LlmResponseIncomplete;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.LlmMeta;
import ru.agimate.agentworker.agent.model.ToolDef;
import ru.agimate.agentworker.workers.LlmCallWorkflow;

import java.util.List;

/**
 * Per-run {@link SimpleAgent.LlmCaller}: enqueues each model request as a child workflow on the
 * llm queue and awaits it. Pure data-returner — holds no persistence/output state and writes no
 * backend records: token usage and the terminal incomplete-reason ride up on the {@link
 * SimpleAgent.LlmReply}; the loop surfaces usage and the run wiring reports it.
 */
class LlmCallDispatcher implements SimpleAgent.LlmCaller {

    private final DBOS dbos;
    private final LlmCallWorkflow llm;
    private final Queue llmQueue;
    private final String agentId;

    LlmCallDispatcher(DBOS dbos, LlmCallWorkflow llm, Queue llmQueue, String agentId) {
        this.dbos = dbos;
        this.llm = llm;
        this.llmQueue = llmQueue;
        this.agentId = agentId;
    }

    @Override
    public SimpleAgent.LlmReply call(List<AgentChatMessage> messages, List<ToolDef> toolDefs) {
        WorkflowHandle<LlmCallWorkflow.Result, ? extends Exception> handle =
                dbos.startWorkflow(() -> llm.llmCall(messages, toolDefs, agentId), new StartWorkflowOptions(llmQueue));
        LlmCallWorkflow.Result result = WorkflowHandles.await(handle);
        // A failure (HTTP/API) is terminal and carries no usage, so we throw straight away. Incomplete
        // (truncation) we do NOT throw here: its tokens are already spent — we return the usage plus the reason
        // on the reply, so the loop first accounts for the spending and only then breaks off (one principle:
        // side records are written by the run wiring, not by the dispatcher).
        if (result.failed()) {
            throw new LlmCallError(result.statusCode(), result.message(), result.userFacing());
        }
        LlmMeta meta = new LlmMeta(result.finishReason(), result.model(), result.callId(),
                result.reasoning());
        return new SimpleAgent.LlmReply(result.assistant(), meta, result.usage(),
                incompleteReason(result.finishReason()), completion(result.finishReason()));
    }

    /**
     * Provider {@code finish_reason} → the loop's stop/continue signal. Case-insensitive because the
     * value is not one dialect: OpenAI sends {@code tool_calls} on the wire, Spring AI hands us the
     * SDK enum name ({@code TOOL_CALLS}), and gateways add their own spellings. Anything else —
     * {@code end_turn}, {@code eos}, absent — is {@code UNKNOWN}, and the loop falls back to the
     * shape of the message.
     */
    static SimpleAgent.Completion completion(String finishReason) {
        if (finishReason == null) {
            return SimpleAgent.Completion.UNKNOWN;
        }
        return switch (finishReason.trim().toLowerCase()) {
            case "tool_calls", "toolcalls", "function_call" -> SimpleAgent.Completion.TOOL_CALLS;
            case "stop" -> SimpleAgent.Completion.STOP;
            default -> SimpleAgent.Completion.UNKNOWN;
        };
    }

    /**
     * Provider {@code finish_reason} → terminal incomplete reason, or {@code null} for a normal
     * finish. Only {@code length}/{@code content_filter} (and the {@code max_tokens} alias) are
     * terminal; {@code stop}, {@code tool_calls}, unknown values and absence all continue the loop.
     */
    static LlmResponseIncomplete.Reason incompleteReason(String finishReason) {
        if (finishReason == null) {
            return null;
        }
        return switch (finishReason.trim().toLowerCase()) {
            case "length", "max_tokens" -> LlmResponseIncomplete.Reason.LENGTH;
            case "content_filter" -> LlmResponseIncomplete.Reason.CONTENT_FILTER;
            default -> null;
        };
    }
}
