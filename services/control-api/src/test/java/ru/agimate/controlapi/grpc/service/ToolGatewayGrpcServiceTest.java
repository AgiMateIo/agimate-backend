package ru.agimate.controlapi.grpc.service;

import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.DetachToolRequest;
import ru.agimate.agentworker.DetachToolResponse;
import ru.agimate.agentworker.GetToolResultRequest;
import ru.agimate.agentworker.GetToolResultResponse;
import ru.agimate.agentworker.ToolResultStatus;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.grpc.auth.WorkerPoolContext;
import ru.agimate.controlapi.grpc.auth.WorkerPoolContextHolder;
import ru.agimate.controlapi.service.tool.AgentToolCallService;
import ru.agimate.controlapi.service.tool.ToolCallLogService;
import ru.agimate.controlapi.service.trigger.RunActivityService;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Контракт детача на gRPC-поверхности: DETACHED побеждает любую другую ветку {@code GetToolResult}
 * (готовый результат, отмену), а {@code DetachTool} возвращает либо DETACHED, либо результат
 * вызова, успевшего завершиться.
 */
@DisplayName("ToolGatewayGrpcService — детач и владение результатом")
class ToolGatewayGrpcServiceTest {

    private final AgentToolCallService agentToolCallService = mock(AgentToolCallService.class);
    private final ToolCallLogService toolCallLogService = mock(ToolCallLogService.class);
    private final AgentRunRepository agentRunRepository = mock(AgentRunRepository.class);
    private final RunActivityService runActivityService = mock(RunActivityService.class);

    private final ToolGatewayGrpcService service = new ToolGatewayGrpcService(
            agentToolCallService, toolCallLogService, agentRunRepository, runActivityService);

    private final UUID agentId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    private <T> T inWorkerContext(java.util.concurrent.Callable<T> call) throws Exception {
        Context ctx = Context.current().withValue(
                WorkerPoolContextHolder.CONTEXT_KEY, new WorkerPoolContext("pool-1", "worker-1"));
        return ctx.call(call);
    }

    private GetToolResultResponse getToolResult() throws Exception {
        AtomicReference<GetToolResultResponse> response = new AtomicReference<>();
        StreamObserver<GetToolResultResponse> observer = new StreamObserver<>() {
            @Override public void onNext(GetToolResultResponse value) { response.set(value); }
            @Override public void onError(Throwable t) { throw new AssertionError(t); }
            @Override public void onCompleted() { }
        };
        inWorkerContext(() -> {
            service.getToolResult(GetToolResultRequest.newBuilder()
                    .setAgentId(agentId.toString()).setToolCallId("call-1")
                    .setRunId(runId.toString()).build(), observer);
            return null;
        });
        return response.get();
    }

    private DetachToolResponse detachTool() throws Exception {
        AtomicReference<DetachToolResponse> response = new AtomicReference<>();
        StreamObserver<DetachToolResponse> observer = new StreamObserver<>() {
            @Override public void onNext(DetachToolResponse value) { response.set(value); }
            @Override public void onError(Throwable t) { throw new AssertionError(t); }
            @Override public void onCompleted() { }
        };
        inWorkerContext(() -> {
            service.detachTool(DetachToolRequest.newBuilder()
                    .setAgentId(agentId.toString()).setToolCallId("call-1")
                    .setRunId(runId.toString()).build(), observer);
            return null;
        });
        return response.get();
    }

    @Test
    @DisplayName("DETACHED первым: детачнутый вызов с готовым результатом всё равно DETACHED")
    void detachedBeatsFinishedResult() throws Exception {
        when(agentToolCallService.getToolCallLog(agentId, "call-1")).thenReturn(ToolCallLog.builder()
                .detachedAt(LocalDateTime.now())
                .finishAt(LocalDateTime.now())
                .output("{\"ok\":1}")
                .build());

        GetToolResultResponse response = getToolResult();

        assertEquals(ToolResultStatus.TOOL_RESULT_STATUS_DETACHED, response.getStatus());
        assertTrue(response.getOutputJson().isEmpty());
    }

    @Test
    @DisplayName("DETACHED сильнее отмены: реплей детачнутого вызова при отменённом ране пишет тот же interim")
    void detachedBeatsCancellation() throws Exception {
        when(agentToolCallService.getToolCallLog(agentId, "call-1")).thenReturn(ToolCallLog.builder()
                .detachedAt(LocalDateTime.now())
                .build());
        when(agentRunRepository.isCancelRequested(runId)).thenReturn(true);

        assertEquals(ToolResultStatus.TOOL_RESULT_STATUS_DETACHED, getToolResult().getStatus());
    }

    @Test
    @DisplayName("DetachTool: штамп лёг — DETACHED")
    void detachStampsPendingCall() throws Exception {
        when(toolCallLogService.detach(agentId, "call-1")).thenReturn(ToolCallLog.builder()
                .detachedAt(LocalDateTime.now())
                .build());

        assertEquals(ToolResultStatus.TOOL_RESULT_STATUS_DETACHED, detachTool().getStatus());
    }

    @Test
    @DisplayName("DetachTool против завершившегося вызова — готовый результат вместо штампа")
    void detachAgainstFinishedCallReturnsResult() throws Exception {
        when(toolCallLogService.detach(agentId, "call-1")).thenReturn(ToolCallLog.builder()
                .finishAt(LocalDateTime.now())
                .output("{\"ok\":1}")
                .build());

        DetachToolResponse response = detachTool();

        assertEquals(ToolResultStatus.TOOL_RESULT_STATUS_SUCCESS, response.getStatus());
        assertEquals("{\"ok\":1}", response.getOutputJson().toStringUtf8());
    }

    @Test
    @DisplayName("DetachTool против упавшего вызова — его ошибка")
    void detachAgainstFailedCallReturnsError() throws Exception {
        when(toolCallLogService.detach(agentId, "call-1")).thenReturn(ToolCallLog.builder()
                .finishAt(LocalDateTime.now())
                .error("boom")
                .build());

        DetachToolResponse response = detachTool();

        assertEquals(ToolResultStatus.TOOL_RESULT_STATUS_ERROR, response.getStatus());
        assertEquals("boom", response.getError());
        assertNull(nullIfEmpty(response.getOutputJson().toStringUtf8()));
    }

    private static String nullIfEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
