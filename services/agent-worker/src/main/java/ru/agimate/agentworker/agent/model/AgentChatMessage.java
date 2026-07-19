package ru.agimate.agentworker.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The worker's own message model — used both to drive the turn loop and, via
 * {@link MessageCodec}, to (de)serialize session history. Deliberately independent of Spring AI
 * (greenfield: the Java worker owns its history JSON) and mapped to Spring AI messages only at
 * model-call time. One instance is one conversation turn message.
 *
 * <p>{@code ignoreUnknown} keeps old history readable after the format gains fields; the compact
 * constructor normalizes absent lists so consumers can stream them unguarded.
 *
 * @param role        who produced the message
 * @param text        user/assistant text ({@code null} for a tool-result message)
 * @param thinking    the assistant emitted reasoning this turn (drives the 💭 progress marker)
 * @param toolCalls   tool calls requested by an assistant message (empty otherwise)
 * @param toolResults results carried by a tool message (empty otherwise)
 * @param parts       inbound file refs on a user message (empty otherwise); bytes fetched at
 *                    LLM-call time, so only the refs live here — safe to checkpoint
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentChatMessage(
        Role role,
        String text,
        boolean thinking,
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

    /** An LLM-requested tool call. {@code argumentsJson} is the raw JSON arguments string. */
    public record ToolCall(String id, String name, String argumentsJson) {}

    /**
     * A tool call's result. {@code contentJson} is the raw JSON the tool returned (or an
     * {@code {"error": ...}} object when {@code failed}).
     */
    public record ToolResult(String id, String name, String contentJson, boolean failed) {}

    public static AgentChatMessage system(String text) {
        return new AgentChatMessage(Role.SYSTEM, text, false, List.of(), List.of(), List.of());
    }

    public static AgentChatMessage user(String text) {
        return new AgentChatMessage(Role.USER, text, false, List.of(), List.of(), List.of());
    }

    public static AgentChatMessage user(String text, List<FilePartRef> parts) {
        return new AgentChatMessage(Role.USER, text, false, List.of(), List.of(), parts);
    }

    public static AgentChatMessage assistant(String text, boolean thinking, List<ToolCall> toolCalls) {
        return new AgentChatMessage(Role.ASSISTANT, text, thinking, toolCalls, List.of(), List.of());
    }

    public static AgentChatMessage toolResults(List<ToolResult> results) {
        return new AgentChatMessage(Role.TOOL, null, false, List.of(), results, List.of());
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
