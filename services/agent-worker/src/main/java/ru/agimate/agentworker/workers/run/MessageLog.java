package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.workflow.StepOptions;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.MessageKind;
import ru.agimate.agentworker.ProgressType;
import ru.agimate.agentworker.agent.MessageCodec;
import ru.agimate.agentworker.grpc.AgentWorkerClient;
import ru.agimate.agentworker.grpc.ControlApiCallException;

/**
 * The run's single writer of dialogue events ({@code SaveMessage}): inbound ack, progress lines,
 * the final answer and error notices. Persistence and channel delivery both happen backend-side —
 * the worker only records what happened, in order.
 *
 * <p>Each call is a durable step; the per-run {@code seq} counter increments deterministically
 * around the checkpoints, so a DBOS replay re-sends the same {@code (trigger_id, seq)} pairs and
 * the backend dedupes instead of double-posting. Created per run (not a Spring bean).
 */
@Slf4j
public class MessageLog {

    private final DBOS dbos;
    private final AgentWorkerClient client;
    private final String agentId;
    private final String triggerId;
    private int seq = 0;

    public MessageLog(DBOS dbos, AgentWorkerClient client, String agentId, String triggerId) {
        this.dbos = dbos;
        this.client = client;
        this.agentId = agentId;
        this.triggerId = triggerId;
    }

    /** Ack «агент получил» (seq 0, до prepare_context): текст не шлём, канонику бэк берёт сам. */
    public void inbound() {
        send(MessageKind.MESSAGE_KIND_INBOUND, ProgressType.PROGRESS_TYPE_UNSPECIFIED, "");
    }

    public void progress(MessageCodec.ProgressLine line) {
        send(MessageKind.MESSAGE_KIND_PROGRESS, line.type(), line.text());
    }

    public void answer(String text) {
        send(MessageKind.MESSAGE_KIND_ANSWER, ProgressType.PROGRESS_TYPE_UNSPECIFIED, text);
    }

    public void error(String text) {
        send(MessageKind.MESSAGE_KIND_ERROR, ProgressType.PROGRESS_TYPE_UNSPECIFIED, text);
    }

    private void send(MessageKind kind, ProgressType progressType, String text) {
        int n = seq++;
        boolean duplicate = dbos.runStep(
                () -> client.saveMessage(agentId, triggerId, n, kind, progressType, text).getDuplicate(),
                new StepOptions("save_message").withMaxAttempts(3)
                        .withShouldRetry(ControlApiCallException::retriableInStep));
        if (duplicate) {
            log.debug("saveMessage duplicate seq={} kind={}", n, kind);
        }
        if (kind != MessageKind.MESSAGE_KIND_PROGRESS && kind != MessageKind.MESSAGE_KIND_INBOUND) {
            log.info("saved [{}]", kind);
        }
    }
}
