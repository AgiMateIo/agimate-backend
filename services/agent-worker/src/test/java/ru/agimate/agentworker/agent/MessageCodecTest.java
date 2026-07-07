package ru.agimate.agentworker.agent;

import ru.agimate.agentworker.agent.model.AgentChatMessage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageCodecTest {

    @Nested
    @DisplayName("serialize / deserialize round-trip")
    class RoundTrip {
        @Test
        @DisplayName("assistant with tool calls survives a round-trip")
        void assistant() {
            AgentChatMessage msg = AgentChatMessage.assistant("preamble", true,
                    List.of(new AgentChatMessage.ToolCall("id1", "board__get", "{\"a\":1}")));
            AgentChatMessage back = MessageCodec.deserialize(MessageCodec.serialize(msg));
            assertEquals(msg, back);
        }

        @Test
        @DisplayName("tool-result message survives a round-trip")
        void toolResult() {
            AgentChatMessage msg = AgentChatMessage.toolResults(
                    List.of(new AgentChatMessage.ToolResult("id1", "board__get", "{\"ok\":true}", false)));
            assertEquals(msg, MessageCodec.deserialize(MessageCodec.serialize(msg)));
        }
    }

    @Nested
    @DisplayName("messageText projection")
    class MessageText {
        @Test
        @DisplayName("tool calls project to comma-joined display names")
        void toolCalls() {
            AgentChatMessage a = AgentChatMessage.assistant("p", false,
                    List.of(new AgentChatMessage.ToolCall("i", "n", "{}")));
            assertEquals("get, put", MessageCodec.messageText(a, List.of("get", "put")));
        }

        @Test
        @DisplayName("assistant/user text project to content; tool-result messages project to null")
        void textAndNull() {
            assertEquals("hello", MessageCodec.messageText(
                    AgentChatMessage.assistant("hello", false, List.of()), List.of()));
            // A user turn (e.g. an injected steer) surfaces its text in the timeline.
            assertEquals("hi", MessageCodec.messageText(AgentChatMessage.user("hi"), List.of()));
            // A tool-result message has no timeline text.
            assertNull(MessageCodec.messageText(
                    AgentChatMessage.toolResults(List.of(
                            new AgentChatMessage.ToolResult("id", "t", "{}", false))), List.of()));
        }
    }

    @Nested
    @DisplayName("progressMessages projection")
    class Progress {
        @Test
        @DisplayName("thinking marker, preamble text and one line per tool")
        void full() {
            AgentChatMessage a = AgentChatMessage.assistant("thinking out loud", true,
                    List.of(new AgentChatMessage.ToolCall("i", "n", "{}")));
            List<String> lines = MessageCodec.progressMessages(a, List.of("get_tasks"));
            assertTrue(lines.get(0).contains("thinking..."));
            assertTrue(lines.contains("thinking out loud"));
            assertTrue(lines.get(lines.size() - 1).contains("get_tasks"));
        }

        @Test
        @DisplayName("no tools → no text echoed (final answer sent separately)")
        void noTools() {
            AgentChatMessage a = AgentChatMessage.assistant("final answer", false, List.of());
            assertTrue(MessageCodec.progressMessages(a, List.of()).isEmpty());
        }
    }
}
