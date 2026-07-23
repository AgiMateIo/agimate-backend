package ru.agimate.agentworker.agent.model;

/**
 * Token counts of one model call, self-contained for accounting: the loop surfaces it via a sink
 * and the run wiring ({@code AgentRunCore}) reports it — the parent is the sole writer of backend
 * side-records. Carries its own {@code callId} (idempotency key = the {@code llm_call} workflow id)
 * and {@code model}/{@code providerId} so the usage sink needs nothing from {@link LlmMeta}.
 */
public record LlmUsage(
        String callId,
        String providerId,
        String model,
        int promptTokens,
        int completionTokens,
        int cacheReadTokens,
        int cacheWriteTokens
) {}
