package ru.agimate.agentworker.agent;

import ru.agimate.agentworker.agent.model.AgentChatMessage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Таблица истинности {@code AgiMateAgent.classify}: finish_reason × наличие вызовов × наличие текста,
 * все двенадцать клеток. Пока решение было разложено по трём {@code if} внутри цикла, каждую клетку
 * приходилось проверять полным прогоном рана — здесь она проверяется вызовом чистой функции.
 */
class AgiMateAgentVerdictTest {

    private static final List<AgentChatMessage.ToolCall> CALLS =
            List.of(new AgentChatMessage.ToolCall("id", "t", "{}"));

    static Stream<Arguments> table() {
        return Stream.of(
                // Ни текста, ни вызовов — пустой ход при любом finish_reason.
                Arguments.of(AgiMateAgent.Completion.STOP, false, null, AgiMateAgent.Verdict.EMPTY),
                Arguments.of(AgiMateAgent.Completion.TOOL_CALLS, false, "  ", AgiMateAgent.Verdict.EMPTY),
                Arguments.of(AgiMateAgent.Completion.UNKNOWN, false, "", AgiMateAgent.Verdict.EMPTY),

                // STOP: модель закончила — ответ, даже если вызовы в сообщении остались.
                Arguments.of(AgiMateAgent.Completion.STOP, false, "готово", AgiMateAgent.Verdict.ANSWER),
                Arguments.of(AgiMateAgent.Completion.STOP, true, "готово", AgiMateAgent.Verdict.ANSWER),
                Arguments.of(AgiMateAgent.Completion.STOP, true, null, AgiMateAgent.Verdict.ANSWER),

                // TOOL_CALLS: модель в середине работы — диспатч, а без распарсенных вызовов переспрос.
                Arguments.of(AgiMateAgent.Completion.TOOL_CALLS, true, "сейчас посмотрю", AgiMateAgent.Verdict.DISPATCH),
                Arguments.of(AgiMateAgent.Completion.TOOL_CALLS, true, null, AgiMateAgent.Verdict.DISPATCH),
                Arguments.of(AgiMateAgent.Completion.TOOL_CALLS, false, "сейчас посмотрю", AgiMateAgent.Verdict.CALLS_LOST),

                // UNKNOWN: провайдер не сказал ничего внятного — решает форма сообщения.
                Arguments.of(AgiMateAgent.Completion.UNKNOWN, true, "сейчас посмотрю", AgiMateAgent.Verdict.DISPATCH),
                Arguments.of(AgiMateAgent.Completion.UNKNOWN, true, null, AgiMateAgent.Verdict.DISPATCH),
                Arguments.of(AgiMateAgent.Completion.UNKNOWN, false, "готово", AgiMateAgent.Verdict.ANSWER));
    }

    @ParameterizedTest(name = "{0}, вызовы={1}, текст=\"{2}\" → {3}")
    @DisplayName("вердикт хода: finish_reason × вызовы × текст")
    @MethodSource("table")
    void classify(AgiMateAgent.Completion completion, boolean hasCalls, String text,
                  AgiMateAgent.Verdict expected) {
        AgentChatMessage assistant =
                AgentChatMessage.assistant(text, false, hasCalls ? CALLS : List.of());
        AgiMateAgent.LlmReply reply =
                new AgiMateAgent.LlmReply(assistant, null, null, null, completion);

        assertEquals(expected, AgiMateAgent.classify(reply));
    }
}
