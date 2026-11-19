package ru.agimate.agentworker.workers.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import dev.dbos.transact.json.DBOSJavaSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import ru.agimate.agentworker.DetachToolResponse;
import ru.agimate.agentworker.GetToolResultResponse;
import ru.agimate.agentworker.ToolResultStatus;
import ru.agimate.agentworker.config.AgentProperties;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolCallStepTest {

    @Test
    @DisplayName("truncateOutput не трогает вывод в пределах лимита")
    void keepsShortOutput() {
        String output = "{\"ok\":true}";
        assertSame(output, ToolCallStep.truncateOutput(output, 100));
        assertSame(output, ToolCallStep.truncateOutput(output, output.length()));
    }

    @Test
    @DisplayName("длинный вывод обрезается до лимита с явной пометкой")
    void truncatesLongOutput() {
        String output = "x".repeat(150);
        String cut = ToolCallStep.truncateOutput(output, 100);
        assertTrue(cut.startsWith("x".repeat(100)));
        assertTrue(cut.contains("truncated by worker: 150 chars total, first 100 shown"));
    }

    @Test
    @DisplayName("суррогатная пара UTF-16 на границе не рвётся")
    void doesNotSplitSurrogatePair() {
        String output = "ab" + "😀".repeat(10); // 😀 = high+low surrogate
        String cut = ToolCallStep.truncateOutput(output, 5); // граница внутри пары
        assertTrue(cut.contains("first 4 shown"));
        assertEquals("ab😀", cut.substring(0, 4));
    }

    @Test
    @DisplayName("effectiveTimeoutMs: спек не задал бюджет → дефолт воркера")
    void defaultBudgetWhenSpecSilent() {
        assertEquals(60_000L, ToolCallStep.effectiveTimeoutMs(0, 60_000L));
        assertEquals(60_000L, ToolCallStep.effectiveTimeoutMs(-5, 60_000L));
    }

    @Test
    @DisplayName("effectiveTimeoutMs: заявленный бюджет побеждает дефолт")
    void specBudgetOverridesDefault() {
        assertEquals(1_800_000L, ToolCallStep.effectiveTimeoutMs(1800, 60_000L));
        assertEquals(300_000L, ToolCallStep.effectiveTimeoutMs(300, 60_000L));
    }

    @Test
    @DisplayName("effectiveTimeoutMs: бюджет свыше 30 минут клампится")
    void specBudgetClampedToMax() {
        assertEquals(1_800_000L, ToolCallStep.effectiveTimeoutMs(7200, 60_000L));
    }

    @Test
    @DisplayName("detachedInterim — валидный JSON со статусом и task_id")
    void interimIsValidJson() throws Exception {
        JsonNode json = new ObjectMapper().readTree(ToolCallStep.detachedInterim("call-77"));
        assertEquals("detached", json.get("status").asText());
        assertEquals("call-77", json.get("task_id").asText());
        assertTrue(json.get("note").asText().contains("Do not call the tool again"));
    }

    @Test
    @DisplayName("чекпоинты шагов переживают сериализатор DBOS: типы восстанавливаются, содержимого нет")
    void checksRoundTripThroughDbosSerializer() {
        ToolCallStep.Outcomes outcomes = new ToolCallStep.Outcomes(List.of(
                new ToolCallStep.Outcome("a", ToolCallStep.Status.SUCCESS, null),
                new ToolCallStep.Outcome("b", ToolCallStep.Status.FAILED, "rpc rejected")));

        String json = DBOSJavaSerializer.INSTANCE.serialize(outcomes);
        Object back = DBOSJavaSerializer.INSTANCE.deserialize(json);

        assertEquals(outcomes, back);
        assertFalse(json.contains("output"));
    }

    @Test
    @DisplayName("чекпоинты тул-шагов переживают сериализатор DBOS, включая моменты «никогда»")
    void stepCheckpointsRoundTripThroughDbosSerializer() {
        ToolCallStep.IssuedBatch batch = new ToolCallStep.IssuedBatch(
                List.of(new ToolCallStep.Outcome("x", ToolCallStep.Status.FAILED, "rejected")),
                List.of(new ToolCallStep.Issued("a", 1L, 2L, 3L),
                        new ToolCallStep.Issued("b", Long.MAX_VALUE, Long.MAX_VALUE, 9L)));
        ToolCallStep.Observation observation = new ToolCallStep.Observation(
                List.of(new ToolCallStep.Outcome("a", ToolCallStep.Status.SUCCESS, null)),
                List.of(new ToolCallStep.Issued("b", 1L, 2L, 3L)),
                List.of(new ToolCallStep.Issued("c", 4L, 5L, 6L)));
        ToolCallStep.DetachResult detached = new ToolCallStep.DetachResult(
                List.of(new ToolCallStep.Outcome("a", ToolCallStep.Status.DETACHED, null)),
                List.of("b"));

        assertEquals(batch, roundTrip(batch));
        assertEquals(observation, roundTrip(observation));
        assertEquals(detached, roundTrip(detached));
    }

    private static Object roundTrip(Object checkpoint) {
        return DBOSJavaSerializer.INSTANCE.deserialize(DBOSJavaSerializer.INSTANCE.serialize(checkpoint));
    }

    private static AgentProperties.Tool tool(long pollTimeoutMs, long detachAfterMs, long maxWaitMs) {
        AgentProperties.Tool tool = new AgentProperties.Tool();
        tool.setPollTimeout(Duration.ofMillis(pollTimeoutMs));
        tool.setDetachAfter(Duration.ofMillis(detachAfterMs));
        tool.setMaxWait(Duration.ofMillis(maxWaitMs));
        return tool;
    }

    private static ToolCallStep.Call call(String id, int timeoutSeconds) {
        return new ToolCallStep.Call(id, "sheets", "conn-1", "generate_report", "{}", timeoutSeconds);
    }

    /** A call whose instants are all relative to now — for the observe tests, no clock games. */
    private static ToolCallStep.Issued issued(String id, long detachInMs, long waitEndInMs, long deadlineInMs) {
        long now = System.currentTimeMillis();
        return new ToolCallStep.Issued(id, now + detachInMs, now + waitEndInMs, now + deadlineInMs);
    }

    private static GetToolResultResponse status(ToolResultStatus status) {
        return GetToolResultResponse.newBuilder().setStatus(status).build();
    }

    @Nested
    @DisplayName("issue: выдача всех вызовов хода и расписание ожидания")
    class Issue {

        private final AgentWorkerClient client = mock(AgentWorkerClient.class);
        private final ToolCallStep step = new ToolCallStep(client, tool(60_000, 10_000, 120_000));

        @Test
        @DisplayName("все вызовы выдаются до первого ожидания; отказ одного не мешает остальным")
        void issuesAllAndIsolatesRejections() {
            doThrow(new RuntimeException("PERMISSION_DENIED: tool is off"))
                    .when(client).executeToolAsync(eq("a"), any(), any(), any(), any(), any(), any());

            ToolCallStep.IssuedBatch batch = step.issue(List.of(
                    call("a", 0), call("b", 0)), "agent-1", "run-1");

            InOrder order = inOrder(client);
            order.verify(client).executeToolAsync(eq("a"), eq("sheets"), eq("conn-1"), eq("generate_report"),
                    any(), eq("agent-1"), eq("run-1"));
            order.verify(client).executeToolAsync(eq("b"), eq("sheets"), eq("conn-1"), eq("generate_report"),
                    any(), eq("agent-1"), eq("run-1"));
            assertEquals(1, batch.rejected().size());
            assertEquals(ToolCallStep.Status.FAILED, batch.rejected().get(0).status());
            assertTrue(batch.rejected().get(0).error().contains("PERMISSION_DENIED"));
            assertEquals(List.of("b"), batch.accepted().stream().map(ToolCallStep.Issued::toolCallId).toList());
        }

        @Test
        @DisplayName("расписание: порог 10 с, потолок — бюджет, если он меньше max-wait")
        void scheduleTakesThresholdAndBudgetCeiling() {
            long before = System.currentTimeMillis();

            ToolCallStep.Issued p = step.issue(List.of(call("a", 0)), "agent-1", "run-1")
                    .accepted().get(0);

            assertEquals(before + 10_000, p.detachAtMs(), 2_000);
            assertEquals(before + 60_000, p.waitEndAtMs(), 2_000);
            assertEquals(before + 60_000, p.deadlineAtMs(), 2_000);
        }

        @Test
        @DisplayName("долгий тул (медиа, 1800 с): молчаливый потолок раньше бюджета — max-wait")
        void scheduleTakesMaxWaitCeiling() {
            long before = System.currentTimeMillis();

            ToolCallStep.Issued p = step.issue(List.of(call("a", 1800)), "agent-1", "run-1")
                    .accepted().get(0);

            assertEquals(before + 10_000, p.detachAtMs(), 2_000);
            assertEquals(before + 120_000, p.waitEndAtMs(), 2_000);
            assertEquals(before + 1_800_000, p.deadlineAtMs(), 2_000);
        }

        @Test
        @DisplayName("детач выключен: ни порога, ни потолка — только бюджет")
        void detachOffLeavesTheBudgetOnly() {
            ToolCallStep noDetach = new ToolCallStep(client, tool(60_000, 0, 120_000));

            ToolCallStep.Issued p = noDetach.issue(List.of(call("a", 0)), "agent-1", "run-1")
                    .accepted().get(0);

            assertEquals(Long.MAX_VALUE, p.detachAtMs());
            assertEquals(Long.MAX_VALUE, p.waitEndAtMs());
            assertTrue(p.deadlineAtMs() < Long.MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("observe: один взгляд на ожидающие вызовы")
    class Observe {

        private final AgentWorkerClient client = mock(AgentWorkerClient.class);
        private final ToolCallStep step = new ToolCallStep(client, tool(60_000, 10_000, 120_000));
        private final Map<String, String> held = new HashMap<>();

        private ToolCallStep.Observation observe(boolean incoming, ToolCallStep.Issued... pending) {
            return step.observe(List.of(pending), "agent-1", "run-1", held, incoming, Set.of());
        }

        @Test
        @DisplayName("готовый результат — в память и в исходы, ждать нечего")
        void settlesWithContent() {
            when(client.getToolResult("agent-1", "a", "run-1")).thenReturn(GetToolResultResponse.newBuilder()
                    .setStatus(ToolResultStatus.TOOL_RESULT_STATUS_SUCCESS)
                    .setOutputJson(ByteString.copyFromUtf8("{\"ok\":1}")).build());

            ToolCallStep.Observation observation = observe(false, issued("a", 10_000, 60_000, 60_000));

            assertEquals(ToolCallStep.Status.SUCCESS, observation.settled().get(0).status());
            assertEquals("{\"ok\":1}", held.get("a"));
            assertTrue(observation.pending().isEmpty());
            assertTrue(observation.toDetach().isEmpty());
        }

        @Test
        @DisplayName("ошибка, отмена ранраном и уже отцепленный (реплей) — три разных исхода")
        void settlesErrorCancelledAndDetached() {
            when(client.getToolResult("agent-1", "e", "run-1")).thenReturn(GetToolResultResponse.newBuilder()
                    .setStatus(ToolResultStatus.TOOL_RESULT_STATUS_ERROR).setError("boom").build());
            when(client.getToolResult("agent-1", "c", "run-1"))
                    .thenReturn(status(ToolResultStatus.TOOL_RESULT_STATUS_CANCELLED));
            when(client.getToolResult("agent-1", "d", "run-1"))
                    .thenReturn(status(ToolResultStatus.TOOL_RESULT_STATUS_DETACHED));

            ToolCallStep.Observation observation = observe(false,
                    issued("e", 10_000, 60_000, 60_000),
                    issued("c", 10_000, 60_000, 60_000),
                    issued("d", 10_000, 60_000, 60_000));

            assertEquals(List.of(ToolCallStep.Status.ERROR, ToolCallStep.Status.ABANDONED, ToolCallStep.Status.DETACHED),
                    observation.settled().stream().map(ToolCallStep.Outcome::status).toList());
            assertEquals("boom", held.get("e"));
        }

        @Test
        @DisplayName("ниже порога вызов ждёт, даже если входящее уже пришло")
        void waitsBelowThreshold() {
            when(client.getToolResult("agent-1", "a", "run-1"))
                    .thenReturn(status(ToolResultStatus.TOOL_RESULT_STATUS_PENDING));

            ToolCallStep.Observation observation = observe(true, issued("a", 5_000, 60_000, 60_000));

            assertEquals(1, observation.pending().size());
            assertTrue(observation.toDetach().isEmpty());
        }

        @Test
        @DisplayName("порог истёк, но входящего нет — ждём потолка, а не отцепляем по секундомеру")
        void thresholdWithoutIncomingKeepsWaiting() {
            when(client.getToolResult("agent-1", "a", "run-1"))
                    .thenReturn(status(ToolResultStatus.TOOL_RESULT_STATUS_PENDING));

            ToolCallStep.Observation observation = observe(false, issued("a", -1_000, 60_000, 60_000));

            assertEquals(1, observation.pending().size());
            assertTrue(observation.toDetach().isEmpty());
        }

        @Test
        @DisplayName("порог истёк и в сессии есть входящее — вызов отцепляется")
        void thresholdWithIncomingDetaches() {
            when(client.getToolResult("agent-1", "a", "run-1"))
                    .thenReturn(status(ToolResultStatus.TOOL_RESULT_STATUS_PENDING));

            ToolCallStep.Observation observation = observe(true, issued("a", -1_000, 60_000, 60_000));

            assertEquals(List.of("a"), observation.toDetach().stream().map(ToolCallStep.Issued::toolCallId).toList());
            assertTrue(observation.settled().isEmpty());
        }

        @Test
        @DisplayName("потолок истёк без входящего — тоже отцепка: ран держит партицию сессии")
        void ceilingDetachesWithoutIncoming() {
            when(client.getToolResult("agent-1", "a", "run-1"))
                    .thenReturn(status(ToolResultStatus.TOOL_RESULT_STATUS_PENDING));

            ToolCallStep.Observation observation = observe(false, issued("a", -119_000, -1_000, 60_000));

            assertEquals(1, observation.toDetach().size());
        }

        @Test
        @DisplayName("детач выключен и бюджет истёк — TIMEOUT, как до появления детача")
        void budgetEndsAsTimeoutWhenDetachIsOff() {
            ToolCallStep noDetach = new ToolCallStep(client, tool(60_000, 0, 120_000));
            when(client.getToolResult("agent-1", "a", "run-1"))
                    .thenReturn(status(ToolResultStatus.TOOL_RESULT_STATUS_PENDING));

            ToolCallStep.Issued spent = new ToolCallStep.Issued(
                    "a", Long.MAX_VALUE, Long.MAX_VALUE, System.currentTimeMillis() - 1_000);
            ToolCallStep.Observation observation = noDetach.observe(
                    List.of(spent), "agent-1", "run-1", held, false, Set.of());

            assertEquals(ToolCallStep.Status.TIMEOUT, observation.settled().get(0).status());
        }

        @Test
        @DisplayName("детач уже падал: не пробуем снова, ждём бюджет")
        void undetachableWaitsForTheBudget() {
            when(client.getToolResult("agent-1", "a", "run-1"))
                    .thenReturn(status(ToolResultStatus.TOOL_RESULT_STATUS_PENDING));

            ToolCallStep.Observation observation = step.observe(
                    List.of(issued("a", -5_000, -1_000, 60_000)),
                    "agent-1", "run-1", held, true, Set.of("a"));

            assertEquals(1, observation.pending().size());
            assertTrue(observation.toDetach().isEmpty());
        }

        @Test
        @DisplayName("упавший GetToolResult — FAILED одного вызова, остальные продолжаются")
        void followFailureIsIsolated() {
            when(client.getToolResult("agent-1", "a", "run-1")).thenThrow(new RuntimeException("grpc down"));
            when(client.getToolResult("agent-1", "b", "run-1"))
                    .thenReturn(status(ToolResultStatus.TOOL_RESULT_STATUS_PENDING));

            ToolCallStep.Observation observation = observe(false,
                    issued("a", 10_000, 60_000, 60_000), issued("b", 10_000, 60_000, 60_000));

            assertEquals(ToolCallStep.Status.FAILED, observation.settled().get(0).status());
            assertTrue(observation.settled().get(0).error().contains("grpc down"));
            assertEquals(List.of("b"), observation.pending().stream().map(ToolCallStep.Issued::toolCallId).toList());
        }
    }

    @Nested
    @DisplayName("detach: гонка детача с финишем")
    class Detaching {

        private final AgentWorkerClient client = mock(AgentWorkerClient.class);
        private final ToolCallStep step = new ToolCallStep(client, tool(60_000, 10_000, 120_000));
        private final Map<String, String> held = new HashMap<>();
        private final ToolCallStep.Issued due = issued("a", -1_000, 60_000, 60_000);

        private ToolCallStep.DetachResult detach() {
            return step.detach(List.of(due), "agent-1", "run-1", held);
        }

        @Test
        @DisplayName("отцепили — исход DETACHED, содержимого в памяти нет (interim регенерируется)")
        void detaches() {
            when(client.detachTool("agent-1", "a", "run-1")).thenReturn(DetachToolResponse.newBuilder()
                    .setStatus(ToolResultStatus.TOOL_RESULT_STATUS_DETACHED).build());

            ToolCallStep.DetachResult result = detach();

            assertEquals(ToolCallStep.Status.DETACHED, result.settled().get(0).status());
            assertTrue(held.isEmpty());
            assertTrue(result.undetachable().isEmpty());
        }

        @Test
        @DisplayName("детач проиграл гонку — готовый результат отдаётся как обычный")
        void raceLostReturnsPlainResult() {
            when(client.detachTool("agent-1", "a", "run-1")).thenReturn(DetachToolResponse.newBuilder()
                    .setStatus(ToolResultStatus.TOOL_RESULT_STATUS_SUCCESS)
                    .setOutputJson(ByteString.copyFromUtf8("{\"ok\":1}")).build());

            ToolCallStep.DetachResult result = detach();

            assertEquals(ToolCallStep.Status.SUCCESS, result.settled().get(0).status());
            assertEquals("{\"ok\":1}", held.get("a"));
        }

        @Test
        @DisplayName("детач привёз ERROR завершившегося вызова — обычная ошибка, текст в памяти")
        void raceLostToError() {
            when(client.detachTool("agent-1", "a", "run-1")).thenReturn(DetachToolResponse.newBuilder()
                    .setStatus(ToolResultStatus.TOOL_RESULT_STATUS_ERROR).setError("boom").build());

            ToolCallStep.DetachResult result = detach();

            assertEquals(ToolCallStep.Status.ERROR, result.settled().get(0).status());
            assertEquals("boom", held.get("a"));
        }

        @Test
        @DisplayName("упавший DetachTool помечается недетачимым — блокирующий фолбэк до бюджета")
        void failureMarksUndetachable() {
            when(client.detachTool("agent-1", "a", "run-1")).thenThrow(new RuntimeException("grpc down"));

            ToolCallStep.DetachResult result = detach();

            assertTrue(result.settled().isEmpty());
            assertEquals(List.of("a"), result.undetachable());
        }

        @Test
        @DisplayName("непонятный ответ (UNSPECIFIED) — тоже недетачимый, а не выдуманный успех")
        void unspecifiedMarksUndetachable() {
            when(client.detachTool("agent-1", "a", "run-1"))
                    .thenReturn(DetachToolResponse.newBuilder().build());

            assertEquals(List.of("a"), detach().undetachable());
        }

        @Test
        @DisplayName("отцепленных вызывают по одному разу: повторного PENDING в ответе нет")
        void detachIsNotRetriedByTheStepItself() {
            when(client.detachTool("agent-1", "a", "run-1")).thenReturn(DetachToolResponse.newBuilder()
                    .setStatus(ToolResultStatus.TOOL_RESULT_STATUS_DETACHED).build());

            detach();

            verify(client, times(1)).detachTool("agent-1", "a", "run-1");
            verify(client, never()).getToolResult(any(), any(), any());
            assertFalse(held.containsKey("a"));
        }
    }
}
