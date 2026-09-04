package ru.agimate.agentworker.agent.model;

/**
 * Provenance of an assistant turn produced by an LLM call, carried alongside (not inside) the
 * {@link AgentChatMessage}: the message is what the model reads back as history, this is what the
 * ledger keeps about the call. {@code null} for tool-result turns (no LLM call). {@code callId} is
 * minted by the worker ({@code runId-n}) — the join key to {@code llm_usage_log.call_id}.
 *
 * @param reasoning the model's reasoning content for this turn ({@code null} when it did not reason
 *                  or the provider sent none). Written to the ledger once and never re-read: the
 *                  loop does not use it, and after a replay it is {@code null} here.
 */
public record LlmMeta(String finishReason, String model, String callId, String reasoning) {}
