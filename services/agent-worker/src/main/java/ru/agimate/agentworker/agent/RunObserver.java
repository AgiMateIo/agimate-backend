package ru.agimate.agentworker.agent;

import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.LlmMeta;
import ru.agimate.agentworker.agent.model.LlmUsage;

import java.util.List;

/**
 * Observer of the run's loop events — the run wiring projects each into a backend side-record,
 * so the loop stays pure and the parent stays the sole writer. Default no-ops let a caller (or
 * test) handle only the events it cares about; {@link #NOOP} ignores all.
 */
public interface RunObserver {
    /**
     * Fires once with the initial message list <b>before</b> the first model call — exactly what
     * the LLM sees on turn 1 (system + history + trigger). Snapshotted into {@code agent_runs.prompt}.
     */
    default void onStart(List<AgentChatMessage> messages) {}

    /**
     * New dialogue messages plus the LLM {@code meta} of the turn that produced them ({@code null}
     * for tool-result turns — no LLM call). Persistence and channel delivery are its projections.
     */
    default void onMessages(List<AgentChatMessage> messages, LlmMeta meta) {}

    /**
     * Per-call token usage for every model call that reached the provider (happy, imitation, and
     * truncated), before the loop acts on the reply.
     */
    default void onUsage(LlmUsage usage) {}

    /** Has the user asked this run to stop? The wiring knows from answers it already receives — no extra call. */
    default boolean cancelRequested() {
        return false;
    }

    /**
     * Messages of the session that arrived while the run was working (steering), ready to
     * append to the conversation — framed, oldest first; empty when nothing is pending. Called
     * at the seam only. The implementation owns the whole exchange: claiming, recording the
     * turns, and confirming once the model has seen them.
     */
    default List<AgentChatMessage> pollSteering() {
        return List.of();
    }

    RunObserver NOOP = new RunObserver() {};
}
