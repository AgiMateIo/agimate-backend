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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    @Nested
    @DisplayName("детач медленного вызова (grace → DetachTool)")
    class Detaching {

        private final AgentWorkerClient client = mock(AgentWorkerClient.class);
        private final Map<String, String> held = new HashMap<>();

        private ToolCallStep step(long detachAfterMs) {
            AgentProperties.Tool tool = new AgentProperties.Tool();
            tool.setDetachAfter(Duration.ofMillis(detachAfterMs));
            tool.setPollTimeout(Duration.ofSeconds(10));
            return new ToolCallStep(client, tool);
        }

        private ToolCallStep.Outcome call(ToolCallStep step) {
            ToolCallStep.Call call = new ToolCallStep.Call("call-1", "sheets", "conn-1", "generate_report", "{}", 0);
            ToolCallStep.Outcomes outcomes = step.run(List.of(call), "agent-1", "run-1", held);
            assertEquals(1, outcomes.items().size());
            return outcomes.items().get(0);
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
        @DisplayName("grace истёк → DetachTool → DETACHED, содержимого в памяти нет (interim регенерируется)")
        void detachesAfterGrace() {
            pendingPoll();
            when(client.detachTool("agent-1", "call-1", "run-1")).thenReturn(detached());

            ToolCallStep.Outcome outcome = call(step(1));

            assertEquals(ToolCallStep.Status.DETACHED, outcome.status());
            assertNull(outcome.error());
            assertTrue(held.isEmpty());
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

            ToolCallStep.Outcome outcome = call(step(1));

            assertEquals(ToolCallStep.Status.SUCCESS, outcome.status());
            assertEquals("{\"ok\":1}", held.get("call-1"));
        }

        @Test
        @DisplayName("детач привёз ERROR завершившегося вызова — ошибка как обычно, текст в памяти")
        void detachRaceLostToError() {
            pendingPoll();
            when(client.detachTool("agent-1", "call-1", "run-1")).thenReturn(
                    DetachToolResponse.newBuilder()
                            .setStatus(ToolResultStatus.TOOL_RESULT_STATUS_ERROR)
                            .setError("boom")
                            .build());

            ToolCallStep.Outcome outcome = call(step(1));

            assertEquals(ToolCallStep.Status.ERROR, outcome.status());
            assertNull(outcome.error());
            assertEquals("boom", held.get("call-1"));
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

            ToolCallStep.Outcome outcome = call(step(1));

            assertEquals(ToolCallStep.Status.SUCCESS, outcome.status());
            assertEquals("{\"late\":true}", held.get("call-1"));
            verify(client, times(1)).detachTool("agent-1", "call-1", "run-1");
        }

        @Test
        @DisplayName("DETACHED на поллинге (реплей после детача) — тот же статус, DetachTool не зовётся")
        void replaySeesDetachedStatus() {
            when(client.getToolResult("agent-1", "call-1", "run-1")).thenReturn(
                    GetToolResultResponse.newBuilder()
                            .setStatus(ToolResultStatus.TOOL_RESULT_STATUS_DETACHED).build());

            ToolCallStep.Outcome outcome = call(step(1));

            assertEquals(ToolCallStep.Status.DETACHED, outcome.status());
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

            ToolCallStep.Outcome outcome = call(step(0));

            assertEquals(ToolCallStep.Status.SUCCESS, outcome.status());
            verify(client, never()).detachTool(anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("несколько вызовов одного хода")
    class Batch {

        private final AgentWorkerClient client = mock(AgentWorkerClient.class);
        private final Map<String, String> held = new HashMap<>();

        private ToolCallStep step(long pollTimeoutMs) {
            AgentProperties.Tool tool = new AgentProperties.Tool();
            tool.setDetachAfter(Duration.ofSeconds(10));
            tool.setPollTimeout(Duration.ofMillis(pollTimeoutMs));
            return new ToolCallStep(client, tool);
        }

        private static GetToolResultResponse success(String json) {
            return GetToolResultResponse.newBuilder()
                    .setStatus(ToolResultStatus.TOOL_RESULT_STATUS_SUCCESS)
                    .setOutputJson(ByteString.copyFromUtf8(json)).build();
        }

        private static GetToolResultResponse pending() {
            return GetToolResultResponse.newBuilder().setStatus(ToolResultStatus.TOOL_RESULT_STATUS_PENDING).build();
        }

        @Test
        @DisplayName("все вызовы запускаются до первого опроса; исходы — в порядке вызовов, содержимое в памяти по id")
        void issuesAllThenPolls() {
            when(client.getToolResult("agent-1", "a", "run-1")).thenReturn(pending(), success("{\"a\":1}"));
            when(client.getToolResult("agent-1", "b", "run-1")).thenReturn(success("{\"b\":2}"));

            ToolCallStep.Outcomes outcomes = step(10_000).run(List.of(
                    new ToolCallStep.Call("a", "wx", "conn-1", "get_weather", "{\"city\":\"Berlin\"}", 0),
                    new ToolCallStep.Call("b", "wx", "conn-1", "get_weather", "{\"city\":\"Paris\"}", 0)),
                    "agent-1", "run-1", held);

            InOrder order = inOrder(client);
            order.verify(client).executeToolAsync(eq("a"), eq("wx"), eq("conn-1"), eq("get_weather"), any(), eq("agent-1"), eq("run-1"));
            order.verify(client).executeToolAsync(eq("b"), eq("wx"), eq("conn-1"), eq("get_weather"), any(), eq("agent-1"), eq("run-1"));
            order.verify(client).getToolResult("agent-1", "a", "run-1");
            assertEquals(List.of("a", "b"), outcomes.items().stream().map(ToolCallStep.Outcome::toolCallId).toList());
            assertTrue(outcomes.items().stream().allMatch(o -> o.status() == ToolCallStep.Status.SUCCESS));
            assertEquals("{\"a\":1}", held.get("a"));
            assertEquals("{\"b\":2}", held.get("b"));
        }

        @Test
        @DisplayName("отвергнутый ExecuteToolAsync → FAILED с текстом; остальные вызовы идут своим чередом")
        void rejectedIssueIsFailedAlone() {
            doThrow(new RuntimeException("PERMISSION_DENIED: tool is off"))
                    .when(client).executeToolAsync(eq("a"), any(), any(), any(), any(), any(), any());
            when(client.getToolResult("agent-1", "b", "run-1")).thenReturn(success("{}"));

            ToolCallStep.Outcomes outcomes = step(10_000).run(List.of(
                    new ToolCallStep.Call("a", "wx", "conn-1", "get_weather", "{}", 0),
                    new ToolCallStep.Call("b", "wx", "conn-1", "get_weather", "{}", 0)),
                    "agent-1", "run-1", held);

            assertEquals(ToolCallStep.Status.FAILED, outcomes.of("a").status());
            assertTrue(outcomes.of("a").error().contains("PERMISSION_DENIED"));
            assertEquals(ToolCallStep.Status.SUCCESS, outcomes.of("b").status());
            verify(client, never()).getToolResult("agent-1", "a", "run-1");
        }

        @Test
        @DisplayName("бюджет истёк → TIMEOUT без текста; отменённый ран → ABANDONED")
        void timeoutAndAbandoned() {
            when(client.getToolResult("agent-1", "a", "run-1")).thenAnswer(inv -> {
                Thread.sleep(5);
                return pending();
            });
            when(client.getToolResult("agent-1", "b", "run-1")).thenReturn(GetToolResultResponse.newBuilder()
                    .setStatus(ToolResultStatus.TOOL_RESULT_STATUS_CANCELLED).build());

            ToolCallStep.Outcomes outcomes = step(1).run(List.of(
                    new ToolCallStep.Call("a", "wx", "conn-1", "get_weather", "{}", 0),
                    new ToolCallStep.Call("b", "wx", "conn-1", "get_weather", "{}", 0)),
                    "agent-1", "run-1", held);

            assertEquals(ToolCallStep.Status.TIMEOUT, outcomes.of("a").status());
            assertNull(outcomes.of("a").error());
            assertEquals(ToolCallStep.Status.ABANDONED, outcomes.of("b").status());
            assertTrue(held.isEmpty());
        }
    }

    @Test
    @DisplayName("чекпоинт шага переживает сериализатор DBOS: типы восстанавливаются, содержимого нет")
    void outcomesRoundTripThroughDbosSerializer() {
        ToolCallStep.Outcomes outcomes = new ToolCallStep.Outcomes(List.of(
                new ToolCallStep.Outcome("a", ToolCallStep.Status.SUCCESS, null),
                new ToolCallStep.Outcome("b", ToolCallStep.Status.FAILED, "rpc rejected")));

        String json = DBOSJavaSerializer.INSTANCE.serialize(outcomes);
        Object back = DBOSJavaSerializer.INSTANCE.deserialize(json);

        assertEquals(outcomes, back);
        assertFalse(json.contains("output"));
    }
}
