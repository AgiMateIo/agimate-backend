package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.workflow.StepOptions;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.MessageKind;
import ru.agimate.agentworker.ProgressType;
import ru.agimate.agentworker.ToolTurn;
import ru.agimate.agentworker.agent.MessageCodec;
import ru.agimate.agentworker.grpc.AgentWorkerClient;
import ru.agimate.agentworker.grpc.ControlApiCallException;

/**
 * The run's single writer of dialogue events ({@code SaveMessage}): inbound ack, progress lines,
 * the final answer and error notices. Persistence and channel delivery both happen backend-side —
 * the worker only records what happened, in order.
 *
 * <p>Each call is a durable step; the per-run {@code seq} counter increments deterministically
 * around the checkpoints, so a DBOS replay re-sends the same {@code (run_id, seq)} pairs and
 * the backend dedupes instead of double-posting. Created per run (not a Spring bean).
 */
@Slf4j
public class MessageLog {

    /** A durable step must not checkpoint proto, so the answer is reduced to its two flags. */
    private record SaveOutcome(boolean duplicate, boolean cancelled) {}

    private final DBOS dbos;
    private final AgentWorkerClient client;
    private final String agentId;
    private final String runId;
    private int seq = 0;
    private boolean cancelRequested;

    public MessageLog(DBOS dbos, AgentWorkerClient client, String agentId, String runId) {
        this.dbos = dbos;
        this.client = client;
        this.agentId = agentId;
        this.runId = runId;
    }

    /** The «agent received it» ack (seq 0, before prepare_context): no text is sent, the backend takes the canonical form itself. */
    public void inbound() {
        send(MessageKind.MESSAGE_KIND_INBOUND, ProgressType.PROGRESS_TYPE_UNSPECIFIED, "", null);
    }

    /** A progress line; under TOOL_CALL it carries the structural record of the tool turn (v2.1) for history. */
    public void progress(MessageCodec.ProgressLine line) {
        send(MessageKind.MESSAGE_KIND_PROGRESS, line.type(), line.text(), line.toolTurn());
    }

    public void answer(String text) {
        send(MessageKind.MESSAGE_KIND_ANSWER, ProgressType.PROGRESS_TYPE_UNSPECIFIED, text, null);
    }

    public void error(String text) {
        send(MessageKind.MESSAGE_KIND_ERROR, ProgressType.PROGRESS_TYPE_UNSPECIFIED, text, null);
    }

    /**
     * Did the user ask this run to stop? Read off the writes the run makes anyway, the seq 0 ack
     * included — which is how a run cancelled while queued stops before any work. Sticky: a replayed
     * step returns its old checkpointed value and would otherwise «un-cancel» the run halfway.
     */
    public boolean isCancelRequested() {
        return cancelRequested;
    }

    private void send(MessageKind kind, ProgressType progressType, String text, ToolTurn toolTurn) {
        int n = seq++;
        SaveOutcome outcome = dbos.runStep(
                () -> {
                    var response = client.saveMessage(agentId, runId, n, kind, progressType, text, toolTurn);
                    return new SaveOutcome(response.getDuplicate(), response.getCancelled());
                },
                new StepOptions("save_message").withMaxAttempts(3)
                        .withShouldRetry(ControlApiCallException::retriableInStep));
        cancelRequested |= outcome.cancelled();
        boolean duplicate = outcome.duplicate();
        if (duplicate) {
            log.debug("saveMessage duplicate seq={} kind={}", n, kind);
        }
        if (kind != MessageKind.MESSAGE_KIND_PROGRESS && kind != MessageKind.MESSAGE_KIND_INBOUND) {
            log.info("saved [{}]", kind);
        }
    }
}
