package ru.agimate.agentworker.workers.run;

import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.ToolCallRec;
import ru.agimate.agentworker.ToolResultRec;
import ru.agimate.agentworker.TurnRole;
import ru.agimate.agentworker.agent.MessageCodec;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.LlmMeta;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.util.List;

/**
 * Canonical full-fidelity turn ledger writer ({@code SaveTurn}): one record per inbound/assistant/tool
 * {@link AgentChatMessage}, uncapped. Created per run so it shares one {@code turn_index} counter.
 *
 * <p>Unlike {@link MessageLog}, these are <b>not</b> durable steps: a turn is a pure idempotent
 * projection of already-durable data (the LLM/tool child-workflow results), so a DBOS replay
 * re-derives and re-sends the same {@code (run_id, turn_index)} and the backend dedupes — no
 * checkpoint is added, so this needs no drain-before-deploy. The {@code turn_index} advances
 * deterministically (the system prompt is the only message that consumes none), so replay reproduces
 * it exactly.
 *
 * <p>The write stays best-effort — a failure is logged and never fails the run — but the ledger is no
 * longer only observability: the backend assembles the history of later runs from it. A lost turn is
 * therefore not a cosmetic hole; it is caught on the backend when the run finishes (the contiguity of
 * {@code turn_index} and the pairing of the last turn), and a run that fails the check is left out of
 * history whole rather than handed over broken.
 */
@Slf4j
public class TurnLog {

    private final AgentWorkerClient client;
    private final String agentId;
    private final String runId;
    private int turnIndex = 0;

    public TurnLog(AgentWorkerClient client, String agentId, String runId) {
        this.client = client;
        this.agentId = agentId;
        this.runId = runId;
    }

    /**
     * Records a user, assistant or tool message. {@code meta} carries the LLM provenance for the
     * assistant turn ({@code null} for the inbound and tool turns — neither is produced by a model
     * call).
     *
     * <p>The system prompt is the one message that stays out: it is static, large, and already kept
     * once per run in {@code agent_runs.prompt} — a copy on every run would only inflate the table.
     * The run's own inbound turn is recorded by the run wiring before the loop, so it takes index 0
     * and the transcript reads as a dialogue rather than starting from the answer.
     */
    public void record(AgentChatMessage m, LlmMeta meta) {
        switch (m.role()) {
            case USER -> send(TurnRole.TURN_ROLE_USER, m.text(), false, List.of(), List.of(), null);
            case ASSISTANT -> send(TurnRole.TURN_ROLE_ASSISTANT, m.text(), m.thinking(),
                    MessageCodec.toolCallRecs(m.toolCalls()), List.of(), meta);
            case TOOL -> send(TurnRole.TURN_ROLE_TOOL, null, false,
                    List.of(), MessageCodec.toolResultRecs(m.toolResults()), null);
            case SYSTEM -> { /* the system prompt lives in the run's prompt snapshot, not per turn */ }
        }
    }

    private void send(TurnRole role, String text, boolean thinking,
                      List<ToolCallRec> calls, List<ToolResultRec> results, LlmMeta meta) {
        int n = turnIndex++;
        String finishReason = meta != null ? meta.finishReason() : null;
        String model = meta != null ? meta.model() : null;
        String callId = meta != null ? meta.callId() : null;
        // The reasoning text rides on meta, not on the message: only the flag reaches the channel
        // projection, the text goes to the ledger alone.
        String thinkingText = meta != null ? meta.reasoning() : null;
        try {
            boolean duplicate = client.saveTurn(agentId, runId, n, role, text, thinking, thinkingText,
                    calls, results, finishReason, model, callId).getDuplicate();
            if (duplicate) {
                log.debug("saveTurn duplicate idx={} role={}", n, role);
            }
        } catch (Exception e) {
            // The turn journal is observability, not a decision loop: a failure does not fail the run, and a replay backfills it.
            log.warn("saveTurn best-effort failed idx={} role={}: {}", n, role, e.getMessage());
        }
    }
}
