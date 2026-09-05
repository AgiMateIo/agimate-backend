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
 * and answers the loop's two questions off records the run makes anyway. Composes the run's two
 * journals ({@link ChannelMessageLog}, {@link TurnLog}) and its {@link SteeringAbsorber}; only the
 * channel log writes durable steps, the rest is best-effort and idempotent on the backend.
 *
 * <p>The assistant turn is the one record this class does not write: it goes into the ledger
 * inside the {@code llm_call} step ({@link LlmCallDispatcher}), before that step's checkpoint.
 */
@Slf4j
class BackendRunRecorder implements RunRecorder {

    private static final ObjectMapper PROMPT_MAPPER = new ObjectMapper();

    private final AgentWorkerClient client;
    private final ChannelMessageLog channelLog;
    private final TurnLog turnLog;
    private final SteeringAbsorber steering;
    private final ToolRegistry registry;
    private final String agentId;
    private final String runId;

    BackendRunRecorder(AgentWorkerClient client, ChannelMessageLog channelLog, TurnLog turnLog,
                       ToolRegistry registry, ResponseTemplates templates, String agentId, String runId) {
        this.client = client;
        this.channelLog = channelLog;
        this.turnLog = turnLog;
        this.steering = new SteeringAbsorber(client, turnLog, agentId, runId, templates.steeredPrefix());
        this.registry = registry;
        this.agentId = agentId;
        this.runId = runId;
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
     * Each turn as its own records: the assistant's calls go out to the channel before the dispatch
     * (its ledger row was written by the step), the results after it (the ledger plus a history-only
     * TOOL_RESULT line) — the backend rebuilds later runs' history from that pair. An assistant turn
     * also confirms the steering absorbed before it: the model has provably seen the message.
     */
    @Override
    public void onMessages(List<AgentChatMessage> newMessages, LlmMeta meta) {
        if (newMessages.stream().anyMatch(m -> m.role() == AgentChatMessage.Role.ASSISTANT)) {
            steering.confirmOnAssistantTurn();
        }
        for (AgentChatMessage m : newMessages) {
            switch (m.role()) {
                case ASSISTANT -> MessageCodec.progressLines(m, registry.displayNames(m))
                        .forEach(channelLog::progress);
                case TOOL -> {
                    turnLog.record(m, null);
                    channelLog.progress(MessageCodec.toolResultLine(m));
                }
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
        return channelLog.isCancelRequested();
    }

    @Override
    public List<AgentChatMessage> pollSteering() {
        return steering.poll();
    }
}
