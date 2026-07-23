package ru.agimate.agentworker.agent.model;

/**
 * Provenance of an assistant turn produced by an LLM call, carried alongside (not inside) the
 * {@link AgentChatMessage} so the message model stays clean and this rides the DBOS checkpoint
 * only once — on the {@code llm_call} result, not multiplied across the re-fed history list.
 * Attached to the turn ledger for assistant turns; {@code null} for tool-result turns (no LLM call).
 * {@code callId} is the LLM child-workflow id — the join key to {@code llm_usage_log.call_id}.
 */
public record LlmMeta(String finishReason, String model, String callId) {}
