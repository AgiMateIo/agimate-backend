package ru.agimate.agentworker.workers.run;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.agentworker.SaveTurnResponse;
import ru.agimate.agentworker.ToolCallRec;
import ru.agimate.agentworker.ToolResultRec;
import ru.agimate.agentworker.TurnRole;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.LlmMeta;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TurnLog")
class TurnLogTest {

    @Mock
    private AgentWorkerClient client;

    private TurnLog turnLog() {
        return new TurnLog(client, "agent-1", "run-1");
    }

    private void stubOk() {
        when(client.saveTurn(anyString(), anyString(), anyInt(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(SaveTurnResponse.newBuilder().setDuplicate(false).build());
    }

    private static AgentChatMessage assistant() {
        return AgentChatMessage.assistant("preamble", true,
                List.of(new AgentChatMessage.ToolCall("c1", "weather", "{\"city\":\"Berlin\"}")));
    }

    private static AgentChatMessage tool() {
        return AgentChatMessage.toolResults(
                List.of(new AgentChatMessage.ToolResult("c1", "weather", "{\"sky\":\"sunny\"}", false)));
    }

    private static final LlmMeta META = new LlmMeta("tool_calls", "gpt-5-mini", "wf-llm-9", "сначала посчитаю");

    @Test
    @DisplayName("assistant → TOOL_CALL-запись с вызовами + meta (finish/model/call); tool → TOOL_RESULT без meta")
    void recordsAssistantThenToolWithSequentialIndices() {
        stubOk();
        TurnLog turns = turnLog();

        turns.record(assistant(), META);
        turns.record(tool(), null);

        ArgumentCaptor<Integer> idx = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<TurnRole> role = ArgumentCaptor.forClass(TurnRole.class);
        ArgumentCaptor<List<ToolCallRec>> calls = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<ToolResultRec>> results = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> finish = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> model = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> callId = ArgumentCaptor.forClass(String.class);

        verify(client, times(2)).saveTurn(eq("agent-1"), eq("run-1"), idx.capture(), role.capture(),
                any(), any(), calls.capture(), results.capture(),
                finish.capture(), model.capture(), callId.capture());

        assertEquals(List.of(0, 1), idx.getAllValues());
        assertEquals(List.of(TurnRole.TURN_ROLE_ASSISTANT, TurnRole.TURN_ROLE_TOOL), role.getAllValues());
        assertEquals(1, calls.getAllValues().get(0).size());     // assistant несёт вызовы
        assertEquals(0, results.getAllValues().get(0).size());
        assertEquals(0, calls.getAllValues().get(1).size());
        assertEquals(1, results.getAllValues().get(1).size());   // tool несёт результаты
        // assistant несёт meta, tool — нет (нет LLM-вызова).
        assertEquals(List.of("tool_calls", "gpt-5-mini", "wf-llm-9"),
                List.of(finish.getAllValues().get(0), model.getAllValues().get(0), callId.getAllValues().get(0)));
        assertNull(finish.getAllValues().get(1));
        assertNull(model.getAllValues().get(1));
        assertNull(callId.getAllValues().get(1));
    }

    @Test
    @DisplayName("входящий ход → USER-запись без тулов и meta; assistant после него получает индекс 1")
    void recordsInboundTurnFirst() {
        stubOk();
        TurnLog turns = turnLog();

        turns.record(AgentChatMessage.user("посчитай кэшбек"), null);
        turns.record(assistant(), META);

        ArgumentCaptor<Integer> idx = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<TurnRole> role = ArgumentCaptor.forClass(TurnRole.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<ToolCallRec>> calls = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> callId = ArgumentCaptor.forClass(String.class);

        verify(client, times(2)).saveTurn(anyString(), anyString(), idx.capture(), role.capture(),
                text.capture(), any(), calls.capture(), any(), any(), any(),
                callId.capture());

        assertEquals(List.of(0, 1), idx.getAllValues());
        assertEquals(List.of(TurnRole.TURN_ROLE_USER, TurnRole.TURN_ROLE_ASSISTANT), role.getAllValues());
        assertEquals("посчитай кэшбек", text.getAllValues().get(0));
        assertEquals(0, calls.getAllValues().get(0).size());
        assertNull(callId.getAllValues().get(0));   // входящий ход не порождён LLM-вызовом
    }

    @Test
    @DisplayName("system-промпт не проецируется и не тратит индекс: он есть в снимке промпта рана")
    void skipsSystem() {
        stubOk();
        TurnLog turns = turnLog();

        turns.record(AgentChatMessage.system("sys"), null);
        turns.record(assistant(), META);

        ArgumentCaptor<Integer> idx = ArgumentCaptor.forClass(Integer.class);
        verify(client, times(1)).saveTurn(anyString(), anyString(), idx.capture(), any(), any(),
                any(), any(), any(), any(), any(), any());
        assertEquals(0, idx.getValue());
    }

    @Test
    @DisplayName("текст рассуждения берётся из meta, а не из сообщения; на tool-ходе его нет")
    void passesThinkingTextFromMeta() {
        stubOk();
        TurnLog turns = turnLog();

        turns.record(assistant(), META);
        turns.record(tool(), null);

        ArgumentCaptor<String> thinkingText = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).saveTurn(anyString(), anyString(), anyInt(), any(), any(),
                thinkingText.capture(), any(), any(), any(), any(), any());
        assertEquals("сначала посчитаю", thinkingText.getAllValues().get(0));
        assertNull(thinkingText.getAllValues().get(1));   // tool-ход не порождён LLM-вызовом
    }

    @Test
    @DisplayName("best-effort: сбой saveTurn не пробрасывается наружу")
    void swallowsFailure() {
        when(client.saveTurn(anyString(), anyString(), anyInt(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("control-api down"));

        assertDoesNotThrow(() -> turnLog().record(assistant(), META));
    }

    @Test
    @DisplayName("один system-ход не дергает клиента вовсе")
    void systemAloneNoClientCall() {
        TurnLog turns = turnLog();
        turns.record(AgentChatMessage.system("sys"), null);
        verifyNoInteractions(client);
    }
}
