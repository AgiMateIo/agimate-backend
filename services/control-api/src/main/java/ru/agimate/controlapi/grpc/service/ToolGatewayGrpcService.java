package ru.agimate.controlapi.grpc.service;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.grpc.auth.WorkerPoolContextHolder;
import ru.agimate.controlapi.grpc.mapper.ToolGatewayMapper;
import ru.agimate.controlapi.service.tool.AgentToolCallService;
import ru.agimate.controlapi.service.trigger.RunActivityService;
import ru.agimate.agentworker.ExecuteToolAsyncAck;
import ru.agimate.agentworker.ExecuteToolRequest;
import ru.agimate.agentworker.GetToolResultRequest;
import ru.agimate.agentworker.GetToolResultResponse;
import ru.agimate.agentworker.ToolGatewayGrpc;
import ru.agimate.agentworker.ToolResultStatus;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static ru.agimate.controlapi.grpc.support.GrpcSupport.handleError;
import static ru.agimate.controlapi.grpc.support.GrpcSupport.parseUuid;

@Service
@RequiredArgsConstructor
@Slf4j
public class ToolGatewayGrpcService extends ToolGatewayGrpc.ToolGatewayImplBase {

    private final AgentToolCallService agentToolCallService;
    private final AgentRunRepository agentRunRepository;
    private final RunActivityService runActivityService;

    @Override
    public void executeToolAsync(ExecuteToolRequest request, StreamObserver<ExecuteToolAsyncAck> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            touchRun(request.getRunId());
            ToolCallRequest toolCall = ToolGatewayMapper.toToolCallRequest(request, resolveSessionId(request));
            String toolCallId = agentToolCallService.processToolCall(agentId, toolCall);
            log.info("ToolGateway.ExecuteToolAsync ok pool={} agent={} workflow={} tool={} toolCallId={}",
                    poolId, agentId, request.getWorkflowId(), request.getToolName(), toolCallId);
            responseObserver.onNext(ExecuteToolAsyncAck.newBuilder()
                    .setToolCallId(toolCallId)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver, "ExecuteToolAsync pool=" + poolId
                    + " workflow=" + request.getWorkflowId() + " tool=" + request.getToolName());
        }
    }

    /** RPC рана = признак его жизни; протокольная семантика — на протокольном слое. */
    private void touchRun(String runId) {
        if (!runId.isEmpty()) {
            try {
                runActivityService.touch(UUID.fromString(runId));
            } catch (IllegalArgumentException ignored) {
                // не-UUID run_id отбраковывается дальше обычной валидацией
            }
        }
    }

    /**
     * Протокол v2: воркер шлёт run_id рана; сессию (доменный контекст тулов — канал prompt'а)
     * резолвит эта сторона из строки рана. Пустой/неизвестный run_id → null (тул вне канала).
     */
    private String resolveSessionId(ExecuteToolRequest request) {
        if (request.getRunId().isEmpty()) {
            return null;
        }
        try {
            return agentRunRepository.findById(UUID.fromString(request.getRunId()))
                    .map(run -> run.getSessionId() != null ? run.getSessionId().toString() : null)
                    .orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public void getToolResult(GetToolResultRequest request, StreamObserver<GetToolResultResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            touchRun(request.getRunId());
            if (request.getToolCallId().isEmpty()) {
                throw Status.INVALID_ARGUMENT.withDescription("tool_call_id is required").asRuntimeException();
            }
            ToolCallLog logEntry = agentToolCallService.getToolCallLog(agentId, request.getToolCallId());

            GetToolResultResponse.Builder builder = GetToolResultResponse.newBuilder();
            if (logEntry.getFinishAt() == null) {
                builder.setStatus(ToolResultStatus.TOOL_RESULT_STATUS_PENDING);
            } else if (logEntry.getError() != null) {
                builder.setStatus(ToolResultStatus.TOOL_RESULT_STATUS_ERROR)
                        .setError(logEntry.getError());
            } else {
                builder.setStatus(ToolResultStatus.TOOL_RESULT_STATUS_SUCCESS);
                if (logEntry.getOutput() != null) {
                    builder.setOutputJson(ByteString.copyFrom(
                            logEntry.getOutput().getBytes(StandardCharsets.UTF_8)));
                }
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver, "GetToolResult pool=" + poolId
                    + " toolCallId=" + request.getToolCallId());
        }
    }

}
