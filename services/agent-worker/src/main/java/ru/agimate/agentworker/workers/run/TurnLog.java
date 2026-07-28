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
 * Canonical full-fidelity turn ledger writer ({@code SaveTurn}): one record per assistant/tool
 * {@link AgentChatMessage}, uncapped. Created per run so it shares one {@code turn_index} counter.
 *
 * <p>Unlike {@link MessageLog}, these are <b>not</b> durable steps: a turn is a pure idempotent
 * projection of already-durable data (the LLM/tool child-workflow results), so a DBOS replay
 * re-derives and re-sends the same {@code (run_id, turn_index)} and the backend dedupes — no
 * checkpoint is added, so this needs no drain-before-deploy. Best-effort: a failed write is logged
 * and never fails the run (observability, not the decision loop). The {@code turn_index} advances
 * deterministically (only assistant/tool messages consume one), so replay reproduces it exactly.
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
     * Records an assistant or tool message; user/system messages are not projected in this phase.
     * {@code meta} carries the LLM provenance for the assistant turn ({@code null} for tool turns).
     */
    public void record(AgentChatMessage m, LlmMeta meta) {
        switch (m.role()) {
            case ASSISTANT -> send(TurnRole.TURN_ROLE_ASSISTANT, m.text(), m.thinking(),
                    MessageCodec.toolCallRecs(m.toolCalls()), List.of(), meta);
            case TOOL -> send(TurnRole.TURN_ROLE_TOOL, null, false,
                    List.of(), MessageCodec.toolResultRecs(m.toolResults()), null);
            case USER, SYSTEM -> { /* prompt/inbound фиксируется отдельно; здесь не проецируем */ }
        }
    }

    private void send(TurnRole role, String text, boolean thinking,
                      List<ToolCallRec> calls, List<ToolResultRec> results, LlmMeta meta) {
        int n = turnIndex++;
        String finishReason = meta != null ? meta.finishReason() : null;
        String model = meta != null ? meta.model() : null;
        String callId = meta != null ? meta.callId() : null;
        try {
            boolean duplicate = client.saveTurn(agentId, runId, n, role, text, thinking,
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
