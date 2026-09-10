package ru.agimate.agentworker.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The worker's own message model, driving the turn loop and the records it leaves. Deliberately
 * independent of Spring AI, mapped to its messages only at model-call time. One instance is one
 * conversation turn message.
 *
 * <p>{@code ignoreUnknown} keeps old history readable after the format gains fields; the compact
 * constructor normalizes absent lists so consumers can stream them unguarded.
 *
 * @param role        who produced the message
 * @param text        user/assistant text ({@code null} for a tool-result message)
 * @param reasoning   the reasoning the assistant emitted this turn, {@code null} when it did not
 *                    reason. Kept whole rather than as a flag because it goes back to the provider:
 *                    in thinking mode with a tools parameter DeepSeek requires the reasoning of
 *                    every past turn in the request and answers 400 without it
 * @param toolCalls   tool calls requested by an assistant message (empty otherwise)
 * @param toolResults results carried by a tool message (empty otherwise)
 * @param parts       inbound file refs on a user message (empty otherwise); bytes fetched at
 *                    LLM-call time, so only the refs live here — safe to checkpoint
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentChatMessage(
        Role role,
        String text,
        String reasoning,
        List<ToolCall> toolCalls,
        List<ToolResult> toolResults,
        List<FilePartRef> parts
) {
    public AgentChatMessage {
        toolCalls = toolCalls != null ? toolCalls : List.of();
        toolResults = toolResults != null ? toolResults : List.of();
        parts = parts != null ? parts : List.of();
    }

    public enum Role {SYSTEM, USER, ASSISTANT, TOOL}

    /** Drives the 💭 progress marker — derived, so the marker and what goes on the wire cannot disagree. */
    public boolean thinking() {
        return reasoning != null && !reasoning.isBlank();
    }

    /** An LLM-requested tool call. {@code argumentsJson} is the raw JSON arguments string. */
    public record ToolCall(String id, String name, String argumentsJson) {}

    /**
     * A tool call's result. {@code contentJson} is what the model reads: the raw JSON the tool
     * returned, an {@code {"error": ...}} object when {@code failed}, or the compact names list of
     * a disclosure delta. {@code material} says what the result is to the context (the ledger
     * keeps it); the four-argument form is an ordinary result.
     */
    public record ToolResult(String id, String name, String contentJson, boolean failed, ContextMaterial material) {

        public ToolResult {
            material = material != null ? material : ContextMaterial.NONE;
        }

        public ToolResult(String id, String name, String contentJson, boolean failed) {
            this(id, name, contentJson, failed, ContextMaterial.NONE);
        }
    }

    public static AgentChatMessage system(String text) {
        return new AgentChatMessage(Role.SYSTEM, text, null, List.of(), List.of(), List.of());
    }

    public static AgentChatMessage user(String text) {
        return new AgentChatMessage(Role.USER, text, null, List.of(), List.of(), List.of());
    }

    public static AgentChatMessage user(String text, List<FilePartRef> parts) {
        return new AgentChatMessage(Role.USER, text, null, List.of(), List.of(), parts);
    }

    public static AgentChatMessage assistant(String text, String reasoning, List<ToolCall> toolCalls) {
        return new AgentChatMessage(Role.ASSISTANT, text, reasoning, toolCalls, List.of(), List.of());
    }

    public static AgentChatMessage toolResults(List<ToolResult> results) {
        return new AgentChatMessage(Role.TOOL, null, null, List.of(), results, List.of());
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
