package ru.agimate.agentworker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.ProgressType;
import ru.agimate.agentworker.agent.model.AgentChatMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MessageCodec")
class MessageCodecTest {

    @Test
    @DisplayName("thinking + текст + тулы → типизированные строки в порядке thinking/text/tools")
    void fullProgress() {
        AgentChatMessage assistant = AgentChatMessage.assistant("let me check", true,
                List.of(new AgentChatMessage.ToolCall("id1", "board__get_tasks", "{}"),
                        new AgentChatMessage.ToolCall("id2", "time__schedule", "{}")));

        List<MessageCodec.ProgressLine> lines =
                MessageCodec.progressLines(assistant, List.of("get_tasks", "schedule"));

        assertEquals(3, lines.size());
        assertEquals(ProgressType.PROGRESS_TYPE_THINKING, lines.get(0).type());
        assertEquals("\ud83d\udcad thinking...", lines.get(0).text());
        assertEquals(ProgressType.PROGRESS_TYPE_TEXT, lines.get(1).type());
        assertEquals("let me check", lines.get(1).text());
        assertEquals(ProgressType.PROGRESS_TYPE_TOOL_CALL, lines.get(2).type());
        assertEquals("\ud83d\udd27 get_tasks\n\ud83d\udd27 schedule", lines.get(2).text());
    }

    @Test
    @DisplayName("финальный ответ без тулов не эхоится progress-строками (уйдёт как ANSWER)")
    void finalAnswerNotEchoed() {
        AgentChatMessage assistant = AgentChatMessage.assistant("the answer", false, List.of());

        assertTrue(MessageCodec.progressLines(assistant, List.of()).isEmpty());
    }

    @Test
    @DisplayName("thinking без тулов даёт только thinking-строку")
    void thinkingOnly() {
        AgentChatMessage assistant = AgentChatMessage.assistant(null, true, List.of());

        List<MessageCodec.ProgressLine> lines = MessageCodec.progressLines(assistant, List.of());

        assertEquals(1, lines.size());
        assertEquals(ProgressType.PROGRESS_TYPE_THINKING, lines.get(0).type());
    }
}
