package ru.agimate.agentworker.workers.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.agent.MessageCodec;
import ru.agimate.agentworker.agent.ResponseTemplates;
import ru.agimate.agentworker.agent.RunRecorder;
import ru.agimate.agentworker.agent.ToolRegistry;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.LlmMeta;
import ru.agimate.agentworker.agent.model.LlmUsage;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.util.List;

/**
 * The run wiring's side of the loop: the {@link RunRecorder} that turns every loop event into a
 * backend record — the prompt snapshot, the turn ledger, the channel's progress lines, token usage —
 * and answers the loop's two questions off records the run makes anyway. One object per run, so the
 * ledger's {@code turn_index} and the channel's {@code seq} each have a single owner and the loop
 * stays free of transport.
 *
 * <p>Only {@link ChannelMessageLog} writes durable steps; the rest is best-effort and idempotent on the
 * backend (a replay re-derives the same turn indexes and call ids).
 */
@Slf4j
class BackendRunRecorder implements RunRecorder {

    private static final ObjectMapper PROMPT_MAPPER = new ObjectMapper();

    private final AgentWorkerClient client;
    private final ChannelMessageLog messages;
    private final TurnLog turns;
    private final SteeringAbsorber steering;
    private final ToolRegistry registry;
    private final String agentId;
    private final String runId;

    BackendRunRecorder(AgentWorkerClient client, ChannelMessageLog messages, ToolRegistry registry,
                       ResponseTemplates templates, String agentId, String runId) {
        this.client = client;
        this.messages = messages;
        this.turns = new TurnLog(client, agentId, runId);
        this.steering = new SteeringAbsorber(client, turns, agentId, runId, templates.steeredPrefix());
        this.registry = registry;
        this.agentId = agentId;
        this.runId = runId;
    }

    /**
     * Turn 0: the run's own inbound message without the ephemeral prefix — the persistent part of
     * the turn. Not a loop event (the channel already showed the user their own message), so the
     * wiring records it before the loop; without it a direct run's transcript, which has no channel
     * history, would open with the answer.
     */
    void recordInbound(AgentChatMessage initialRequest) {
        turns.record(initialRequest, null);
    }

    /**
     * Snapshot of the starting prompt ({@code agent_runs.prompt}) exactly as it went into the first
     * call, attachments as references. First-write-wins on the backend, so a replay does not
     * overwrite it; best-effort, since the snapshot is observability.
     */
    @Override
    public void onStart(List<AgentChatMessage> startMessages) {
        try {
            client.savePrompt(agentId, runId, PROMPT_MAPPER.writeValueAsString(startMessages));
        } catch (Exception e) {
            log.warn("prompt snapshot report failed (best-effort): {}", e.getMessage());
        }
    }

    /**
     * Each turn as its own records: the assistant with its calls goes out before the dispatch (the
     * ledger plus the channel's TOOL_CALL line), the results after it (the ledger plus a history-only
     * TOOL_RESULT line) — the backend rebuilds later runs' history from that pair. An assistant turn
     * also confirms the steering absorbed before it: the model has provably seen the message.
     */
    @Override
    public void onMessages(List<AgentChatMessage> newMessages, LlmMeta meta) {
        if (newMessages.stream().anyMatch(m -> m.role() == AgentChatMessage.Role.ASSISTANT)) {
            steering.confirmOnAssistantTurn();
        }
        for (AgentChatMessage m : newMessages) {
            turns.record(m, meta);
            switch (m.role()) {
                case ASSISTANT -> MessageCodec.progressLines(m, registry.displayNames(m))
                        .forEach(messages::progress);
                case TOOL -> messages.progress(MessageCodec.toolResultLine(m));
                default -> { }
            }
        }
    }

    /** Token accounting, idempotent by call_id (a replay deduplicates) and skipped without one; best-effort. */
    @Override
    public void onUsage(LlmUsage usage) {
        if (usage.callId() == null || usage.callId().isBlank()) {
            log.warn("no call id — skipping usage report");
            return;
        }
        try {
            client.reportLlmUsage(usage.callId(), agentId, runId, usage.providerId(), usage.model(),
                    usage.promptTokens(), usage.completionTokens(),
                    usage.cacheReadTokens(), usage.cacheWriteTokens());
        } catch (Exception e) {
            log.warn("LLM usage report failed (best-effort): {}", e.getMessage());
        }
    }

    @Override
    public boolean cancelRequested() {
        return messages.isCancelRequested();
    }

    @Override
    public List<AgentChatMessage> pollSteering() {
        return steering.poll();
    }
}
