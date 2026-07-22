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
    @DisplayName("TOOL_CALL-строка несёт calls-половину ToolTurn: преамбула + вызовы, без результатов")
    void callsTurnAttached() {
        AgentChatMessage assistant = AgentChatMessage.assistant("let me check", false,
                List.of(new AgentChatMessage.ToolCall("id1", "board.get_tasks", "{\"boardId\":1}")));

        List<MessageCodec.ProgressLine> lines =
                MessageCodec.progressLines(assistant, List.of("get_tasks"));

        MessageCodec.ProgressLine toolLine = lines.get(lines.size() - 1);
        assertEquals(ProgressType.PROGRESS_TYPE_TOOL_CALL, toolLine.type());
        assertEquals("let me check", toolLine.toolTurn().getText());
        assertEquals(1, toolLine.toolTurn().getCallsCount());
        assertEquals("board.get_tasks", toolLine.toolTurn().getCalls(0).getName());
        assertEquals("{\"boardId\":1}", toolLine.toolTurn().getCalls(0).getArgumentsJson());
        assertEquals(0, toolLine.toolTurn().getResultsCount()); // результатов на этой строке ещё нет
        // Остальные строки — без ToolTurn.
        assertTrue(lines.stream().limit(lines.size() - 1).allMatch(l -> l.toolTurn() == null));
    }

    @Test
    @DisplayName("toolResultLine — results-половина: TOOL_RESULT, пустой текст, только результаты")
    void resultsTurnLine() {
        AgentChatMessage results = AgentChatMessage.toolResults(
                List.of(new AgentChatMessage.ToolResult("id1", "board.get_tasks", "{\"tasks\":[]}", false)));

        MessageCodec.ProgressLine line = MessageCodec.toolResultLine(results);

        assertEquals(ProgressType.PROGRESS_TYPE_TOOL_RESULT, line.type());
        assertEquals("", line.text()); // пустой текст → в канал не доставляется
        assertEquals(0, line.toolTurn().getCallsCount());
        assertEquals(1, line.toolTurn().getResultsCount());
        assertEquals("id1", line.toolTurn().getResults(0).getId());
        assertEquals("{\"tasks\":[]}", line.toolTurn().getResults(0).getOutputJson());
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
