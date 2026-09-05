package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.DBOS;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.agent.AgiMateAgent;
import ru.agimate.agentworker.agent.MessageCodec;
import ru.agimate.agentworker.agent.error.LlmCallError;
import ru.agimate.agentworker.agent.error.LlmResponseIncomplete;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.LlmMeta;
import ru.agimate.agentworker.agent.model.LlmUsage;
import ru.agimate.agentworker.agent.model.ToolDef;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-run {@link AgiMateAgent.LlmCaller}: each model request is one {@code llm_call} durable step
 * of the run workflow. DBOS keeps only the step's output, and that output ({@link Checkpoint}) is
 * identifiers and numbers — the reply itself lives in run memory and, for a crash replay, in the
 * turn ledger: the assistant turn is written inside the step, before the checkpoint commits, so a
 * replay reads it back with {@code GetTurn} instead of calling the model again.
 *
 * <p>Call ids are minted here, {@code runId-n} with {@code n} the ordinal of the call within the
 * run (replayed calls included), so a replay mints the same id — it seeds the tool call ids the
 * backend keys idempotency on, and keys {@code llm_usage_log}.
 */
@Slf4j
class LlmCallDispatcher implements AgiMateAgent.LlmCaller {

    /**
     * The step's checkpoint: what a replay needs to continue the loop without the reply itself.
     * {@code turnIndex} says where the ledger holds the assistant turn; {@code -1} on failure.
     */
    record Checkpoint(boolean failed, Integer statusCode, String message, boolean userFacing,
                      String callId, int turnIndex, String finishReason, String model, LlmUsage usage) {

        static Checkpoint ok(String callId, int turnIndex, LlmMeta meta, LlmUsage usage) {
            return new Checkpoint(false, null, null, false, callId, turnIndex,
                    meta.finishReason(), meta.model(), usage);
        }

        static Checkpoint failure(LlmCall.Reply reply, String callId) {
            return new Checkpoint(true, reply.statusCode(), reply.message(), reply.userFacing(),
                    callId, -1, null, null, null);
        }
    }

    private final DBOS dbos;
    private final LlmCall llmCall;
    private final TurnLog turnLog;
    private final AgentWorkerClient client;
    private final String agentId;
    private final String runId;
    private int calls = 0;

    LlmCallDispatcher(DBOS dbos, LlmCall llmCall, TurnLog turnLog, AgentWorkerClient client,
                      String agentId, String runId) {
        this.dbos = dbos;
        this.llmCall = llmCall;
        this.turnLog = turnLog;
        this.client = client;
        this.agentId = agentId;
        this.runId = runId;
    }

    @Override
    public AgiMateAgent.LlmReply call(List<AgentChatMessage> messages, List<ToolDef> toolDefs) {
        String callId = runId + "-" + calls++;
        // Set by the step body, so it stays empty exactly when the step was replayed from its checkpoint.
        AtomicReference<LlmCall.Reply> held = new AtomicReference<>();
        Checkpoint checkpoint = dbos.runStep(() -> {
            LlmCall.Reply reply = llmCall.call(messages, toolDefs, agentId, callId);
            if (reply.failed()) {
                return Checkpoint.failure(reply, callId);
            }
            int turnIndex = turnLog.record(reply.assistant(), reply.meta());
            held.set(reply);
            return Checkpoint.ok(callId, turnIndex, reply.meta(), reply.usage());
        }, "llm_call");

        // A failure (HTTP/API) is terminal and carries no usage, so we throw straight away. Incomplete
        // (truncation) we do NOT throw here: its tokens are already spent — we return the usage plus the reason
        // on the reply, so the loop first accounts for the spending and only then breaks off.
        if (checkpoint.failed()) {
            throw new LlmCallError(checkpoint.statusCode(), checkpoint.message(), checkpoint.userFacing());
        }
        AgentChatMessage assistant;
        LlmMeta meta;
        LlmCall.Reply reply = held.get();
        if (reply != null) {
            assistant = reply.assistant();
            meta = reply.meta();
        } else {
            log.info("llm_call {} replayed: reading turn {} back from the ledger", callId, checkpoint.turnIndex());
            assistant = MessageCodec.fromTurn(client.getTurn(agentId, runId, checkpoint.turnIndex()));
            turnLog.resumeAfter(checkpoint.turnIndex());
            // The reasoning is not re-read: the loop never uses it and the ledger already has it.
            meta = new LlmMeta(checkpoint.finishReason(), checkpoint.model(), callId, null);
        }
        return new AgiMateAgent.LlmReply(assistant, meta, checkpoint.usage(),
                incompleteReason(checkpoint.finishReason()), completion(checkpoint.finishReason()));
    }

    /**
     * Provider {@code finish_reason} → the loop's stop/continue signal. Case-insensitive because the
     * value is not one dialect: OpenAI sends {@code tool_calls} on the wire, Spring AI hands us the
     * SDK enum name ({@code TOOL_CALLS}), and gateways add their own spellings. Anything else —
     * {@code end_turn}, {@code eos}, absent — is {@code UNKNOWN}, and the loop falls back to the
     * shape of the message.
     */
    static AgiMateAgent.Completion completion(String finishReason) {
        if (finishReason == null) {
            return AgiMateAgent.Completion.UNKNOWN;
        }
        return switch (finishReason.trim().toLowerCase()) {
            case "tool_calls", "toolcalls", "function_call" -> AgiMateAgent.Completion.TOOL_CALLS;
            case "stop" -> AgiMateAgent.Completion.STOP;
            default -> AgiMateAgent.Completion.UNKNOWN;
        };
    }

    /**
     * Provider {@code finish_reason} → terminal incomplete reason, or {@code null} for a normal
     * finish. Only {@code length}/{@code content_filter} (and the {@code max_tokens} alias) are
     * terminal; everything else lets the loop continue.
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
