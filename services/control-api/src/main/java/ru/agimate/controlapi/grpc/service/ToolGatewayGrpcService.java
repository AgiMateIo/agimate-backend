package ru.agimate.controlapi.grpc.service;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.ValidationErrorStatusException;
import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.repositories.TriggerLogAgentRepository;
import ru.agimate.controlapi.grpc.auth.WorkerPoolContextHolder;
import ru.agimate.controlapi.grpc.mapper.ToolGatewayMapper;
import ru.agimate.controlapi.service.tool.AgentToolCallService;
import ru.agimate.agentworker.ExecuteToolAsyncAck;
import ru.agimate.agentworker.ExecuteToolRequest;
import ru.agimate.agentworker.GetToolResultRequest;
import ru.agimate.agentworker.GetToolResultResponse;
import ru.agimate.agentworker.ToolGatewayGrpc;
import ru.agimate.agentworker.ToolResultStatus;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static ru.agimate.controlapi.grpc.support.GrpcSupport.parseUuid;

@Service
@RequiredArgsConstructor
@Slf4j
public class ToolGatewayGrpcService extends ToolGatewayGrpc.ToolGatewayImplBase {

    private final AgentToolCallService agentToolCallService;
    private final TriggerLogAgentRepository triggerLogAgentRepository;

    @Override
    public void executeToolAsync(ExecuteToolRequest request, StreamObserver<ExecuteToolAsyncAck> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            ToolCallRequest toolCall = ToolGatewayMapper.toToolCallRequest(request, resolveSessionId(request));
            String toolCallId = agentToolCallService.processToolCall(agentId, toolCall);
            log.info("ToolGateway.ExecuteToolAsync ok pool={} agent={} workflow={} tool={} toolCallId={}",
                    poolId, agentId, request.getWorkflowId(), request.getToolName(), toolCallId);
            responseObserver.onNext(ExecuteToolAsyncAck.newBuilder()
                    .setToolCallId(toolCallId)
                    .build());
            responseObserver.onCompleted();
        } catch (ForbiddenStatusException e) {
            log.info("ToolGateway.ExecuteToolAsync denied pool={} workflow={} tool={} reason={}",
                    poolId, request.getWorkflowId(), request.getToolName(), e.getMessage());
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription(e.getMessage()).asRuntimeException());
        } catch (NotFoundStatusException e) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage()).asRuntimeException());
        } catch (ConflictStatusException e) {
            responseObserver.onError(Status.ABORTED
                    .withDescription(e.getMessage()).asRuntimeException());
        } catch (BadRequestStatusException | ValidationErrorStatusException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage()).asRuntimeException());
        } catch (io.grpc.StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("ToolGateway.ExecuteToolAsync failed pool={} workflow={}",
                    poolId, request.getWorkflowId(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription(e.getMessage()).asRuntimeException());
        }
    }

    /**
     * Протокол v2: воркер шлёт trigger_id рана; сессию (доменный контекст тулов — канал prompt'а)
     * резолвит эта сторона из строки рана. Пустой/неизвестный trigger_id → null (тул вне канала).
     */
    private String resolveSessionId(ExecuteToolRequest request) {
        if (request.getTriggerId().isEmpty()) {
            return null;
        }
        try {
            return triggerLogAgentRepository.findById(UUID.fromString(request.getTriggerId()))
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
        } catch (NotFoundStatusException e) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage()).asRuntimeException());
        } catch (BadRequestStatusException | ValidationErrorStatusException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage()).asRuntimeException());
        } catch (io.grpc.StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("ToolGateway.GetToolResult failed pool={} toolCallId={}",
                    poolId, request.getToolCallId(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription(e.getMessage()).asRuntimeException());
        }
    }

}
