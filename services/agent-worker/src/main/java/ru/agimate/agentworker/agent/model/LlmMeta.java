package ru.agimate.agentworker.agent.model;

/**
 * Provenance of an assistant turn produced by an LLM call, carried alongside (not inside) the
 * {@link AgentChatMessage}: the message is what the model reads back as history, this is what the
 * ledger keeps about the call. {@code null} for tool-result turns (no LLM call). {@code callId} is
 * minted by the worker ({@code runId-n}) — the join key to {@code llm_usage_log.call_id}.
 *
 * <p>The reasoning is deliberately absent: it belongs to the message, which reads it back to the
 * provider on the next request, and a copy here would be the stale one after a replay.
 */
public record LlmMeta(String finishReason, String model, String callId) {}
