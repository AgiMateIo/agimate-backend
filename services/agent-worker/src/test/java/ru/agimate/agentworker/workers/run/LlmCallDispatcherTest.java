package ru.agimate.agentworker.workers.run;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.agent.SimpleAgent;
import ru.agimate.agentworker.agent.error.LlmResponseIncomplete;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmCallDispatcherTest {

    @Test
    @DisplayName("finish_reason: length/max_tokens → LENGTH, content_filter → CONTENT_FILTER (case-insensitive)")
    void mapsTerminalReasons() {
        assertEquals(LlmResponseIncomplete.Reason.LENGTH,
                LlmCallDispatcher.incompleteReason("length"));
        assertEquals(LlmResponseIncomplete.Reason.LENGTH,
                LlmCallDispatcher.incompleteReason("max_tokens"));
        assertEquals(LlmResponseIncomplete.Reason.LENGTH,
                LlmCallDispatcher.incompleteReason("  LENGTH "));
        assertEquals(LlmResponseIncomplete.Reason.CONTENT_FILTER,
                LlmCallDispatcher.incompleteReason("content_filter"));
    }

    @Test
    @DisplayName("finish_reason: stop/tool_calls/неизвестное/null → не терминально (цикл продолжается)")
    void normalReasonsAreNotTerminal() {
        assertNull(LlmCallDispatcher.incompleteReason("stop"));
        assertNull(LlmCallDispatcher.incompleteReason("tool_calls"));
        assertNull(LlmCallDispatcher.incompleteReason("bogus"));
        assertNull(LlmCallDispatcher.incompleteReason(null));
    }

    @Test
    @DisplayName("completion: TOOL_CALLS/STOP в любом регистре, включая имя enum'а из SDK")
    void mapsCompletion() {
        assertEquals(SimpleAgent.Completion.TOOL_CALLS, LlmCallDispatcher.completion("tool_calls"));
        assertEquals(SimpleAgent.Completion.TOOL_CALLS, LlmCallDispatcher.completion("TOOL_CALLS"));
        assertEquals(SimpleAgent.Completion.TOOL_CALLS, LlmCallDispatcher.completion(" function_call "));
        assertEquals(SimpleAgent.Completion.STOP, LlmCallDispatcher.completion("stop"));
        assertEquals(SimpleAgent.Completion.STOP, LlmCallDispatcher.completion("STOP"));
    }

    @Test
    @DisplayName("completion: чужой диалект и отсутствие значения → UNKNOWN (решает форма сообщения)")
    void unknownCompletion() {
        assertEquals(SimpleAgent.Completion.UNKNOWN, LlmCallDispatcher.completion("end_turn"));
        assertEquals(SimpleAgent.Completion.UNKNOWN, LlmCallDispatcher.completion("eos"));
        assertEquals(SimpleAgent.Completion.UNKNOWN, LlmCallDispatcher.completion(""));
        assertEquals(SimpleAgent.Completion.UNKNOWN, LlmCallDispatcher.completion(null));
    }
}
