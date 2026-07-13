package ru.agimate.agentworker.agent;

import ru.agimate.agentworker.agent.error.AgentRunAborted;
import ru.agimate.agentworker.agent.error.LlmCallError;
import ru.agimate.agentworker.agent.model.AgentChatMessage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRunnerTest {

    private static AgentRunner runner(SimpleAgent.LlmCaller llm, int maxTurns) {
        return new AgentRunner(llm, calls -> List.of(), List.of(), maxTurns, "for test", null);
    }

    private static String runOnce(AgentRunner runner) {
        return runner.run("system", List.of(), AgentChatMessage.user("hi"));
    }

    @Test
    @DisplayName("max turns → AgentRunAborted with the max-turns notice")
    void maxTurns() {
        SimpleAgent.LlmCaller loops = (msgs, defs) -> AgentChatMessage.assistant(null, false,
                List.of(new AgentChatMessage.ToolCall("id", "t", "{}")));
        AgentRunAborted ex = assertThrows(AgentRunAborted.class, () -> runOnce(runner(loops, 2)));
        assertEquals(AgentRunner.MAX_TURNS_NOTICE, ex.userNotice());
    }

    @Test
    @DisplayName("401 LLM error → auth notice; 500 and non-HTTP → generic model notice")
    void llmErrors() {
        AgentRunAborted auth = assertThrows(AgentRunAborted.class,
                () -> runOnce(runner((m, d) -> { throw new LlmCallError(401, "unauthorized"); }, 5)));
        assertEquals(AgentRunner.AUTH_ERROR_NOTICE, auth.userNotice());

        AgentRunAborted http = assertThrows(AgentRunAborted.class,
                () -> runOnce(runner((m, d) -> { throw new LlmCallError(500, "boom"); }, 5)));
        assertEquals(AgentRunner.MODEL_ERROR_NOTICE, http.userNotice());

        AgentRunAborted api = assertThrows(AgentRunAborted.class,
                () -> runOnce(runner((m, d) -> { throw new LlmCallError(null, "timeout"); }, 5)));
        assertEquals(AgentRunner.MODEL_ERROR_NOTICE, api.userNotice());
    }

    @Test
    @DisplayName("userFacing LLM error (напр. квота) → серверный текст дословно в userNotice")
    void userFacingErrorSurfacedVerbatim() {
        String quota = "Дневной лимит токенов платформенной модели исчерпан. Подключите свой ключ.";
        AgentRunAborted aborted = assertThrows(AgentRunAborted.class,
                () -> runOnce(runner((m, d) -> { throw new LlmCallError(null, quota, true); }, 5)));
        assertEquals(quota, aborted.userNotice());
    }

    @Test
    @DisplayName("happy path returns the final answer")
    void happy() {
        assertEquals("ok", runOnce(runner((m, d) -> AgentChatMessage.assistant("ok", false, List.of()), 5)));
    }
}
