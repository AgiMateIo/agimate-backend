package ru.agimate.agentworker.workers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import dev.dbos.transact.DBOS;
import dev.dbos.transact.execution.ThrowingSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.DetachToolResponse;
import ru.agimate.agentworker.GetToolResultResponse;
import ru.agimate.agentworker.ToolResultStatus;
import ru.agimate.agentworker.config.AgentProperties;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolCallWorkflowImplTest {

    @Test
    @DisplayName("truncateOutput не трогает вывод в пределах лимита")
    void keepsShortOutput() {
        String output = "{\"ok\":true}";
        assertSame(output, ToolCallWorkflowImpl.truncateOutput(output, 100));
        assertSame(output, ToolCallWorkflowImpl.truncateOutput(output, output.length()));
    }

    @Test
    @DisplayName("длинный вывод обрезается до лимита с явной пометкой")
    void truncatesLongOutput() {
        String output = "x".repeat(150);
        String cut = ToolCallWorkflowImpl.truncateOutput(output, 100);
        assertTrue(cut.startsWith("x".repeat(100)));
        assertTrue(cut.contains("truncated by worker: 150 chars total, first 100 shown"));
    }

    @Test
    @DisplayName("суррогатная пара UTF-16 на границе не рвётся")
    void doesNotSplitSurrogatePair() {
        String output = "ab" + "😀".repeat(10); // 😀 = high+low surrogate
        String cut = ToolCallWorkflowImpl.truncateOutput(output, 5); // граница внутри пары
        assertTrue(cut.contains("first 4 shown"));
        assertEquals("ab😀", cut.substring(0, 4));
    }

    @Test
    @DisplayName("effectiveTimeoutMs: спек не задал бюджет → дефолт воркера")
    void defaultBudgetWhenSpecSilent() {
        assertEquals(60_000L, ToolCallWorkflowImpl.effectiveTimeoutMs(0, 60_000L));
        assertEquals(60_000L, ToolCallWorkflowImpl.effectiveTimeoutMs(-5, 60_000L));
    }

    @Test
    @DisplayName("effectiveTimeoutMs: заявленный бюджет побеждает дефолт")
    void specBudgetOverridesDefault() {
        assertEquals(1_800_000L, ToolCallWorkflowImpl.effectiveTimeoutMs(1800, 60_000L));
        assertEquals(300_000L, ToolCallWorkflowImpl.effectiveTimeoutMs(300, 60_000L));
    }

    @Test
    @DisplayName("effectiveTimeoutMs: бюджет свыше 30 минут клампится")
    void specBudgetClampedToMax() {
        assertEquals(1_800_000L, ToolCallWorkflowImpl.effectiveTimeoutMs(7200, 60_000L));
    }

    @Test
    @DisplayName("detachedInterim — валидный JSON со статусом и task_id")
    void interimIsValidJson() throws Exception {
        JsonNode json = new ObjectMapper().readTree(ToolCallWorkflowImpl.detachedInterim("call-77"));
        assertEquals("detached", json.get("status").asText());
        assertEquals("call-77", json.get("task_id").asText());
        assertTrue(json.get("note").asText().contains("Do not call the tool again"));
    }

    @Nested
    @DisplayName("детач медленного вызова (grace → DetachTool)")
    class Detaching {

        private final AgentWorkerClient client = mock(AgentWorkerClient.class);

        @SuppressWarnings("unchecked")
        private ToolCallWorkflowImpl workflow(long detachAfterMs) {
            AgentProperties.Tool tool = new AgentProperties.Tool();
            tool.setDetachAfter(Duration.ofMillis(detachAfterMs));
            tool.setPollTimeout(Duration.ofSeconds(10));
            DBOS dbos = mock(DBOS.class);
            try {
                when(dbos.runStep(any(ThrowingSupplier.class), anyString()))
                        .thenAnswer(inv -> inv.getArgument(0, ThrowingSupplier.class).execute());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return new ToolCallWorkflowImpl(client, dbos, tool);
        }

        private ToolCallWorkflow.Outcome call(ToolCallWorkflowImpl workflow) {
            return workflow.toolCall("sheets", "generate_report", "{}",
                    "call-1", "agent-1", "run-1", "conn-1", 0);
        }

        /** PENDING с задержкой, чтобы часы гарантированно прошли миллисекундный grace. */
        private void pendingPoll() {
            when(client.getToolResult("agent-1", "call-1", "run-1")).thenAnswer(inv -> {
                Thread.sleep(5);
                return GetToolResultResponse.newBuilder()
                        .setStatus(ToolResultStatus.TOOL_RESULT_STATUS_PENDING).build();
            });
        }

        private static DetachToolResponse detached() {
            return DetachToolResponse.newBuilder()
                    .setStatus(ToolResultStatus.TOOL_RESULT_STATUS_DETACHED).build();
        }

        @Test
        @DisplayName("grace истёк → DetachTool → interim с task_id вместо результата")
        void detachesAfterGrace() {
            pendingPoll();
            when(client.detachTool("agent-1", "call-1", "run-1")).thenReturn(detached());

            ToolCallWorkflow.Outcome outcome = call(workflow(1));

            assertNull(outcome.error());
            assertTrue(outcome.outputJson().contains("\"detached\""));
            assertTrue(outcome.outputJson().contains("call-1"));
        }

        @Test
        @DisplayName("детач проиграл гонку — готовый результат отдаётся как обычный")
        void detachRaceLostReturnsPlainResult() {
            pendingPoll();
            when(client.detachTool("agent-1", "call-1", "run-1")).thenReturn(
                    DetachToolResponse.newBuilder()
                            .setStatus(ToolResultStatus.TOOL_RESULT_STATUS_SUCCESS)
                            .setOutputJson(ByteString.copyFromUtf8("{\"ok\":1}"))
                            .build());

            ToolCallWorkflow.Outcome outcome = call(workflow(1));

            assertEquals("{\"ok\":1}", outcome.outputJson());
        }

        @Test
        @DisplayName("детач привёз ERROR завершившегося вызова — ошибка как обычно")
        void detachRaceLostToError() {
            pendingPoll();
            when(client.detachTool("agent-1", "call-1", "run-1")).thenReturn(
                    DetachToolResponse.newBuilder()
                            .setStatus(ToolResultStatus.TOOL_RESULT_STATUS_ERROR)
                            .setError("boom")
                            .build());

            ToolCallWorkflow.Outcome outcome = call(workflow(1));

            assertNull(outcome.outputJson());
            assertTrue(outcome.error().contains("boom"));
        }

        @Test
        @DisplayName("упавший DetachTool — блокирующий фолбэк до старого бюджета, второй раз не детачим")
        void detachFailureFallsBackToBlocking() {
            when(client.getToolResult("agent-1", "call-1", "run-1"))
                    .thenAnswer(inv -> {
                        Thread.sleep(5);
                        return GetToolResultResponse.newBuilder()
                                .setStatus(ToolResultStatus.TOOL_RESULT_STATUS_PENDING).build();
                    })
                    .thenReturn(GetToolResultResponse.newBuilder()
                            .setStatus(ToolResultStatus.TOOL_RESULT_STATUS_SUCCESS)
                            .setOutputJson(ByteString.copyFromUtf8("{\"late\":true}"))
                            .build());
            when(client.detachTool("agent-1", "call-1", "run-1"))
                    .thenThrow(new RuntimeException("grpc down"));

            ToolCallWorkflow.Outcome outcome = call(workflow(1));

            assertEquals("{\"late\":true}", outcome.outputJson());
            verify(client, times(1)).detachTool("agent-1", "call-1", "run-1");
        }

        @Test
        @DisplayName("DETACHED на поллинге (реплей после детача) — тот же interim, DetachTool не зовётся")
        void replaySeesDetachedStatus() {
            when(client.getToolResult("agent-1", "call-1", "run-1")).thenReturn(
                    GetToolResultResponse.newBuilder()
                            .setStatus(ToolResultStatus.TOOL_RESULT_STATUS_DETACHED).build());

            ToolCallWorkflow.Outcome outcome = call(workflow(1));

            assertTrue(outcome.outputJson().contains("\"detached\""));
            verify(client, never()).detachTool(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("detach-after ≤ 0 выключает детач: ждём по-старому")
        void nonPositiveGraceDisablesDetaching() {
            when(client.getToolResult("agent-1", "call-1", "run-1"))
                    .thenAnswer(inv -> {
                        Thread.sleep(5);
                        return GetToolResultResponse.newBuilder()
                                .setStatus(ToolResultStatus.TOOL_RESULT_STATUS_PENDING).build();
                    })
                    .thenReturn(GetToolResultResponse.newBuilder()
                            .setStatus(ToolResultStatus.TOOL_RESULT_STATUS_SUCCESS)
                            .setOutputJson(ByteString.copyFromUtf8("{}"))
                            .build());

            ToolCallWorkflow.Outcome outcome = call(workflow(0));

            assertEquals("{}", outcome.outputJson());
            verify(client, never()).detachTool(anyString(), anyString(), anyString());
        }
    }
}
