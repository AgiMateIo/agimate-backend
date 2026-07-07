package ru.agimate.agentworker.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure (de)serialization of {@link AgentChatMessage} to/from the {@code message_json} bytes
 * persisted as session history, plus the human-readable text projections the backend timeline
 * and the channel progress stream use. No DBOS or transport here. Greenfield format: the Java
 * worker owns its history JSON (it never has to read pydantic-ai messages).
 */
public final class MessageCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String THINKING_EMOJI = "💭";
    private static final String TOOL_EMOJI = "🔧";

    private MessageCodec() {
    }

    public static byte[] serialize(AgentChatMessage msg) {
        try {
            return MAPPER.writeValueAsBytes(msg);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize message", e);
        }
    }

    public static AgentChatMessage deserialize(byte[] json) {
        try {
            return MAPPER.readValue(json, AgentChatMessage.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize message", e);
        }
    }

    /**
     * Human-readable projection of a message for the timeline ({@code AppendMessage.text}). A user
     * message (e.g. an injected steer) → its content. An assistant message: tool calls →
     * comma-joined backend tool names; else text → its content; else thinking → the
     * {@code "thinking..."} marker. Tool-result / system messages → {@code null}.
     */
    public static String messageText(AgentChatMessage msg, List<String> toolDisplayNames) {
        if (msg.role() == AgentChatMessage.Role.USER) {
            return msg.text() != null && !msg.text().isEmpty() ? msg.text() : null;
        }
        if (msg.role() != AgentChatMessage.Role.ASSISTANT) {
            return null;
        }
        if (toolDisplayNames != null && !toolDisplayNames.isEmpty()) {
            return String.join(", ", toolDisplayNames);
        }
        if (msg.text() != null && !msg.text().isEmpty()) {
            return msg.text();
        }
        return msg.thinking() ? "thinking..." : null;
    }

    /**
     * Channel-facing progress lines for one assistant message: a thinking marker (if it reasoned),
     * the preamble text written alongside tool calls, and one {@code 🔧 <name>} line per tool. Text
     * is emitted only when tools are present, so the final tool-less answer is not echoed here — it
     * is sent once via {@code OutboundPublisher.answer} after the loop.
     */
    public static List<String> progressMessages(AgentChatMessage assistant, List<String> toolDisplayNames) {
        List<String> messages = new ArrayList<>();
        if (assistant.thinking()) {
            messages.add(THINKING_EMOJI + " thinking...");
        }
        if (toolDisplayNames == null || toolDisplayNames.isEmpty()) {
            return messages;
        }
        if (assistant.text() != null && !assistant.text().isEmpty()) {
            messages.add(assistant.text());
        }
        StringBuilder toolLines = new StringBuilder();
        for (int i = 0; i < toolDisplayNames.size(); i++) {
            if (i > 0) {
                toolLines.append("\n");
            }
            toolLines.append(TOOL_EMOJI).append(" ").append(toolDisplayNames.get(i));
        }
        messages.add(toolLines.toString());
        return messages;
    }
}
