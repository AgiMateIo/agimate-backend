package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.execution.ThrowingSupplier;
import dev.dbos.transact.workflow.StepOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import ru.agimate.agentworker.ConnectorToolSpec;
import ru.agimate.agentworker.MessageKind;
import ru.agimate.agentworker.ProgressType;
import ru.agimate.agentworker.ReportLlmUsageResponse;
import ru.agimate.agentworker.SaveMessageResponse;
import ru.agimate.agentworker.SavePromptResponse;
import ru.agimate.agentworker.SaveTurnResponse;
import ru.agimate.agentworker.ToolTurn;
import ru.agimate.agentworker.TurnRole;
import ru.agimate.agentworker.agent.TestTemplates;
import ru.agimate.agentworker.agent.ToolRegistry;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.LlmMeta;
import ru.agimate.agentworker.agent.model.LlmUsage;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Пиновка проводки рана: какие записи и в каком порядке уезжают на бэк с одного хода. Порядок
 * {@code seq} у {@code SaveMessage} — контракт реплея DBOS, поэтому тест намеренно
 * характеризационный: меняется форма записей на ход — меняется и он, вместе с drain перед выкаткой.
 */
@DisplayName("BackendRunRecorder")
class RunRecorderTest {

    private final AgentWorkerClient client = mock(AgentWorkerClient.class);
    private ChannelMessageLog channelLog;
    private TurnLog turnLog;
    private BackendRunRecorder recorder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        DBOS dbos = mock(DBOS.class);
        when(dbos.runStep(any(ThrowingSupplier.class), any(StepOptions.class)))
                .thenAnswer(inv -> inv.getArgument(0, ThrowingSupplier.class).execute());
        when(client.saveMessage(anyString(), anyString(), anyInt(), any(), any(), any(), any()))
                .thenReturn(SaveMessageResponse.newBuilder().build());
        when(client.saveTurn(anyString(), anyString(), anyInt(), any(), any(), any(), any(), any(),
                any(), any(), any()))
                .thenReturn(SaveTurnResponse.newBuilder().build());
        channelLog = new ChannelMessageLog(dbos, client, "agent-1", "run-1");
        ToolRegistry registry = ToolRegistry.build(List.of(ConnectorToolSpec.newBuilder()
                .setConnectorCode("wx").setNamespace("wx").setName("get_weather").setConnectionId("conn-1")
                .build()));
        turnLog = new TurnLog(client, "agent-1", "run-1");
        recorder = new BackendRunRecorder(client, channelLog, turnLog, registry, TestTemplates.of("ru"), "agent-1", "run-1");
    }

    private static AgentChatMessage assistantCalling() {
        return AgentChatMessage.assistant("looking it up", false,
                List.of(new AgentChatMessage.ToolCall("c1", "wx__get_weather", "{\"city\":\"Berlin\"}")));
    }

    private static AgentChatMessage toolAnswer() {
        return AgentChatMessage.toolResults(
                List.of(new AgentChatMessage.ToolResult("c1", "wx__get_weather", "{\"sky\":\"sunny\"}", false)));
    }

    @Test
    @DisplayName("тул-ход: ассистент → TOOL_CALL-строка (его ход в журнал пишет шаг llm_call), результаты → журнал + TOOL_RESULT-строка")
    void toolTurnIsTwoLedgerRecordsAndTwoProgressLines() {
        LlmMeta meta = new LlmMeta("tool_calls", "gpt-5-mini", "call-9", null);
        channelLog.inbound();
        turnLog.record(AgentChatMessage.user("weather in Berlin?"), null);
        // What the llm_call step does before the recorder sees the turn.
        turnLog.record(assistantCalling(), meta);

        recorder.onMessages(List.of(assistantCalling()), meta);
        recorder.onMessages(List.of(toolAnswer()), null);

        InOrder order = inOrder(client);
        order.verify(client).saveMessage(eq("agent-1"), eq("run-1"), eq(0),
                eq(MessageKind.MESSAGE_KIND_INBOUND), any(), any(), any());
        order.verify(client).saveTurn(eq("agent-1"), eq("run-1"), eq(0), eq(TurnRole.TURN_ROLE_USER),
                eq("weather in Berlin?"), any(), any(), any(), any(), any(), any());
        order.verify(client).saveTurn(eq("agent-1"), eq("run-1"), eq(1), eq(TurnRole.TURN_ROLE_ASSISTANT),
                eq("looking it up"), any(), any(), any(), eq("tool_calls"), eq("gpt-5-mini"), eq("call-9"));
        // The channel sees the backend tool name, not the sanitized one the model used.
        ArgumentCaptor<ToolTurn> callsTurn = ArgumentCaptor.forClass(ToolTurn.class);
        order.verify(client).saveMessage(eq("agent-1"), eq("run-1"), eq(1),
                eq(MessageKind.MESSAGE_KIND_PROGRESS), eq(ProgressType.PROGRESS_TYPE_TEXT),
                eq("looking it up"), any());
        order.verify(client).saveMessage(eq("agent-1"), eq("run-1"), eq(2),
                eq(MessageKind.MESSAGE_KIND_PROGRESS), eq(ProgressType.PROGRESS_TYPE_TOOL_CALL),
                eq("🔧 get_weather"), callsTurn.capture());
        order.verify(client).saveTurn(eq("agent-1"), eq("run-1"), eq(2), eq(TurnRole.TURN_ROLE_TOOL),
                any(), any(), any(), any(), any(), any(), any());
        order.verify(client).saveMessage(eq("agent-1"), eq("run-1"), eq(3),
                eq(MessageKind.MESSAGE_KIND_PROGRESS), eq(ProgressType.PROGRESS_TYPE_TOOL_RESULT),
                eq(""), any());
        assertEquals(1, callsTurn.getValue().getCallsCount());
        assertEquals(0, callsTurn.getValue().getResultsCount());
        // The recorder never writes the assistant turn itself: one ledger row for it, from the step.
        verify(client, times(1)).saveTurn(eq("agent-1"), eq("run-1"), anyInt(), eq(TurnRole.TURN_ROLE_ASSISTANT),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("снимок промпта: JSON-массив стартовых сообщений одним SavePrompt")
    void startSnapshotsThePrompt() {
        when(client.savePrompt(anyString(), anyString(), anyString()))
                .thenReturn(SavePromptResponse.newBuilder().build());

        recorder.onStart(List.of(AgentChatMessage.system("be brief"), AgentChatMessage.user("hi")));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(client).savePrompt(eq("agent-1"), eq("run-1"), json.capture());
        assertTrue(json.getValue().startsWith("[{"));
        assertTrue(json.getValue().contains("\"be brief\""));
    }

    @Test
    @DisplayName("usage: отчёт по call_id; без call_id отчёта нет")
    void usageIsReportedByCallId() {
        when(client.reportLlmUsage(anyString(), anyString(), anyString(), any(), any(),
                anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(ReportLlmUsageResponse.newBuilder().build());

        recorder.onUsage(new LlmUsage("call-9", "prov-1", "gpt-5-mini", 10, 5, 0, 0));
        recorder.onUsage(new LlmUsage(null, "prov-1", "gpt-5-mini", 10, 5, 0, 0));

        verify(client).reportLlmUsage("call-9", "agent-1", "run-1", "prov-1", "gpt-5-mini", 10, 5, 0, 0);
        verify(client, never()).reportLlmUsage(eq(""), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("отмена читается с ответов SaveMessage и залипает")
    void cancellationIsReadOffTheWrites() {
        when(client.saveMessage(anyString(), anyString(), anyInt(), any(), any(), any(), any()))
                .thenReturn(SaveMessageResponse.newBuilder().setCancelled(true).build())
                .thenReturn(SaveMessageResponse.newBuilder().build());

        assertFalse(recorder.cancelRequested());
        channelLog.inbound();
        assertTrue(recorder.cancelRequested());
        recorder.onMessages(List.of(toolAnswer()), null);
        assertTrue(recorder.cancelRequested());
    }
}
