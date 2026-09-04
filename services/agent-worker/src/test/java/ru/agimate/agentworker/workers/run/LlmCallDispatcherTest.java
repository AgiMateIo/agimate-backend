package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.execution.ThrowingSupplier;
import dev.dbos.transact.json.DBOSJavaSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.agimate.agentworker.GetTurnResponse;
import ru.agimate.agentworker.SaveTurnResponse;
import ru.agimate.agentworker.ToolCallRec;
import ru.agimate.agentworker.TurnRole;
import ru.agimate.agentworker.agent.AgiMateAgent;
import ru.agimate.agentworker.agent.error.LlmCallError;
import ru.agimate.agentworker.agent.error.LlmResponseIncomplete;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.LlmMeta;
import ru.agimate.agentworker.agent.model.LlmUsage;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
        assertEquals(AgiMateAgent.Completion.TOOL_CALLS, LlmCallDispatcher.completion("tool_calls"));
        assertEquals(AgiMateAgent.Completion.TOOL_CALLS, LlmCallDispatcher.completion("TOOL_CALLS"));
        assertEquals(AgiMateAgent.Completion.TOOL_CALLS, LlmCallDispatcher.completion(" function_call "));
        assertEquals(AgiMateAgent.Completion.STOP, LlmCallDispatcher.completion("stop"));
        assertEquals(AgiMateAgent.Completion.STOP, LlmCallDispatcher.completion("STOP"));
    }

    @Test
    @DisplayName("completion: чужой диалект и отсутствие значения → UNKNOWN (решает форма сообщения)")
    void unknownCompletion() {
        assertEquals(AgiMateAgent.Completion.UNKNOWN, LlmCallDispatcher.completion("end_turn"));
        assertEquals(AgiMateAgent.Completion.UNKNOWN, LlmCallDispatcher.completion("eos"));
        assertEquals(AgiMateAgent.Completion.UNKNOWN, LlmCallDispatcher.completion(""));
        assertEquals(AgiMateAgent.Completion.UNKNOWN, LlmCallDispatcher.completion(null));
    }

    /**
     * Реплей: {@code runStep} отдаёт чекпоинт, не вызывая supplier — единственный тест, который
     * проходит ветку чтения хода по id.
     */
    @Nested
    @DisplayName("шаг llm_call: чекпоинт без текста, реплей читает ход через GetTurn")
    class Step {

        private final DBOS dbos = mock(DBOS.class);
        private final LlmCall llm = mock(LlmCall.class);
        private final AgentWorkerClient client = mock(AgentWorkerClient.class);
        private final TurnLog turns = new TurnLog(client, "agent-1", "run-1");
        private final LlmCallDispatcher dispatcher = new LlmCallDispatcher(dbos, llm, turns, client, "agent-1", "run-1");

        private static final AgentChatMessage ASSISTANT = AgentChatMessage.assistant("looking it up", true,
                List.of(new AgentChatMessage.ToolCall("c1", "wx__get_weather", "{\"city\":\"Berlin\"}")));
        private static final LlmMeta META = new LlmMeta("tool_calls", "gpt-5-mini", "run-1-0", "hmm");
        private static final LlmUsage USAGE = new LlmUsage("run-1-0", "prov-1", "gpt-5-mini", 10, 5, 0, 0);

        @SuppressWarnings("unchecked")
        private void stepRuns() throws Exception {
            when(dbos.runStep(any(ThrowingSupplier.class), eq("llm_call")))
                    .thenAnswer(inv -> inv.getArgument(0, ThrowingSupplier.class).execute());
        }

        @SuppressWarnings("unchecked")
        private void stepReplays(LlmCallDispatcher.Checkpoint checkpoint) throws Exception {
            when(dbos.runStep(any(ThrowingSupplier.class), eq("llm_call"))).thenReturn(checkpoint);
        }

        @Test
        @DisplayName("обычный путь: ход ассистента в журнал внутри шага, чекпоинт — id и числа, ответ из памяти")
        void normalPathWritesTurnInsideTheStep() throws Exception {
            stepRuns();
            when(llm.call(any(), any(), eq("agent-1"), eq("run-1-0"))).thenReturn(LlmCall.Reply.ok(ASSISTANT, META, USAGE));
            when(client.saveTurn(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(SaveTurnResponse.newBuilder().build());

            AgiMateAgent.LlmReply reply = dispatcher.call(List.of(AgentChatMessage.user("hi")), List.of());

            assertSame(ASSISTANT, reply.message());
            assertEquals("hmm", reply.meta().reasoning());
            assertEquals(AgiMateAgent.Completion.TOOL_CALLS, reply.completion());
            verify(client).saveTurn(eq("agent-1"), eq("run-1"), eq(0), eq(TurnRole.TURN_ROLE_ASSISTANT),
                    eq("looking it up"), eq("hmm"), any(), any(), eq("tool_calls"), eq("gpt-5-mini"), eq("run-1-0"));
            verify(client, never()).getTurn(any(), any(), anyInt());

            ArgumentCaptor<ThrowingSupplier<?, ?>> step = ArgumentCaptor.forClass(ThrowingSupplier.class);
            verify(dbos).runStep(step.capture(), eq("llm_call"));
            Object checkpoint = step.getValue().execute();
            String json = DBOSJavaSerializer.INSTANCE.serialize(checkpoint);
            assertFalse(json.contains("looking it up"));
            assertFalse(json.contains("Berlin"));
            assertFalse(json.contains("hmm"));
            assertEquals(checkpoint, DBOSJavaSerializer.INSTANCE.deserialize(json));
        }

        @Test
        @DisplayName("реплей: supplier не зовётся, ход читается GetTurn по индексу из чекпоинта, счётчик журнала продолжает за ним")
        void replayReadsTheTurnBack() throws Exception {
            stepReplays(LlmCallDispatcher.Checkpoint.ok("run-1-0", 4, META, USAGE));
            when(client.getTurn("agent-1", "run-1", 4)).thenReturn(GetTurnResponse.newBuilder()
                    .setRole(TurnRole.TURN_ROLE_ASSISTANT).setText("looking it up").setThinking(true)
                    .addToolCalls(ToolCallRec.newBuilder().setId("c1").setName("wx__get_weather")
                            .setArgumentsJson("{\"city\":\"Berlin\"}"))
                    .build());
            when(client.saveTurn(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(SaveTurnResponse.newBuilder().build());

            AgiMateAgent.LlmReply reply = dispatcher.call(List.of(AgentChatMessage.user("hi")), List.of());

            assertEquals(ASSISTANT, reply.message());
            assertEquals("run-1-0", reply.meta().callId());
            assertNull(reply.meta().reasoning());
            assertEquals(USAGE, reply.usage());
            verifyNoInteractions(llm);
            assertEquals(5, turns.record(AgentChatMessage.toolResults(List.of()), null));
        }

        @Test
        @DisplayName("отказ провайдера: чекпоинт с кодом и текстом, LlmCallError без хода в журнале")
        void failureBecomesAnError() throws Exception {
            stepRuns();
            when(llm.call(any(), any(), eq("agent-1"), eq("run-1-0"))).thenReturn(LlmCall.Reply.failure(503, "upstream down"));

            LlmCallError error = assertThrows(LlmCallError.class,
                    () -> dispatcher.call(List.of(AgentChatMessage.user("hi")), List.of()));

            assertEquals(503, error.statusCode());
            assertEquals("upstream down", error.getMessage());
            verify(client, never()).saveTurn(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("callId = runId-порядковый номер, считая и реплеенные вызовы")
        void callIdsAreOrdinal() throws Exception {
            stepReplays(LlmCallDispatcher.Checkpoint.ok("run-1-0", 1, META, null));
            when(client.getTurn(any(), any(), anyInt())).thenReturn(GetTurnResponse.newBuilder()
                    .setRole(TurnRole.TURN_ROLE_ASSISTANT).setText("ok").build());
            dispatcher.call(List.of(), List.of());

            stepRuns();
            when(llm.call(any(), any(), eq("agent-1"), eq("run-1-1"))).thenReturn(LlmCall.Reply.ok(ASSISTANT, META, null));
            when(client.saveTurn(any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(SaveTurnResponse.newBuilder().build());
            dispatcher.call(List.of(), List.of());

            verify(llm).call(any(), any(), eq("agent-1"), eq("run-1-1"));
        }
    }
}
