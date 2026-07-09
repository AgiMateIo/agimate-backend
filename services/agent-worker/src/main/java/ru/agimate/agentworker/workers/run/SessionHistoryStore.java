package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.DBOS;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.GetHistoryResponse;
import ru.agimate.agentworker.HistoryMessage;
import ru.agimate.agentworker.agent.MessageCodec;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-run session persistence: restores the history window and appends each turn over gRPC as
 * durable DBOS steps, tracking the next free turn index. Single-writer-per-session is guaranteed
 * upstream by the partitioned {@code agent_exec} queue, so the running index needs no locking.
 */
@Slf4j
class SessionHistoryStore {

    private static final int HISTORY_WINDOW_MESSAGES = 50;

    private final DBOS dbos;
    private final AgentWorkerClient client;
    private final String agentId;
    private final String sessionPubId;
    private final String runId;
    private int nextTurnIdx = 0;

    SessionHistoryStore(DBOS dbos, AgentWorkerClient client, String agentId, String sessionPubId, String runId) {
        this.dbos = dbos;
        this.client = client;
        this.agentId = agentId;
        this.sessionPubId = sessionPubId;
        this.runId = runId;
    }

    /** History slice plus the next free turn index (derived from the server's turn_idx). */
    private record SessionHistory(List<AgentChatMessage> messages, int nextTurnIdx) {}

    /** Load the last-N history window and seed the next turn index from the server's turn_idx. */
    List<AgentChatMessage> restore() {
        SessionHistory history = dbos.runStep(() -> {
            GetHistoryResponse resp = client.getHistory(agentId, sessionPubId, HISTORY_WINDOW_MESSAGES);
            List<AgentChatMessage> messages = new ArrayList<>();
            for (HistoryMessage m : resp.getMessagesList()) {
                messages.add(MessageCodec.deserialize(m.getMessageJson().toByteArray()));
            }
            return new SessionHistory(messages, nextTurnIdx(resp));
        }, "get_session_history");
        // Set the index from the step's (replay-cached) result, never inside the step body.
        this.nextTurnIdx = history.nextTurnIdx();
        log.info("history: {} message(s), next turn {}", history.messages().size(), this.nextTurnIdx);
        return history.messages();
    }

    /** Append messages at the current turn index (idempotent by {@code (session, starting_turn_idx)}). */
    void append(List<AgentWorkerClient.AppendItem> items) {
        int startingTurnIdx = nextTurnIdx;
        List<Integer> assigned = dbos.runStep(
                () -> client.appendSessionMessages(agentId, sessionPubId, runId, startingTurnIdx, items),
                "append_session_messages");
        nextTurnIdx += assigned.size();
    }

    /**
     * Next free turn index: one past the highest persisted {@code turn_idx} — never the slice
     * size. {@code GetHistory} is capped at {@link #HISTORY_WINDOW_MESSAGES}, so once a session
     * outgrows the window, {@code size()} would collide with already-taken indices and the
     * server's idempotent insert (ON CONFLICT DO NOTHING) would silently drop every new message.
     */
    static int nextTurnIdx(GetHistoryResponse resp) {
        int max = -1;
        for (HistoryMessage m : resp.getMessagesList()) {
            max = Math.max(max, m.getTurnIdx());
        }
        return max + 1;
    }
}
