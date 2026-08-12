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
import ru.agimate.controlapi.service.tool.ToolCallLogService;
import ru.agimate.controlapi.service.trigger.RunActivityService;
import ru.agimate.agentworker.DetachToolRequest;
import ru.agimate.agentworker.DetachToolResponse;
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
    private final ToolCallLogService toolCallLogService;
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

    /** Has the user asked this run to stop? An empty or non-UUID run_id (a call outside a run) — no. */
    private boolean cancelRequested(String runId) {
        if (runId.isEmpty()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(agentRunRepository.isCancelRequested(UUID.fromString(runId)));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** A run's RPC is its sign of life; the protocol semantics belong on the protocol layer. */
    private void touchRun(String runId) {
        if (!runId.isEmpty()) {
            try {
                runActivityService.touch(UUID.fromString(runId));
            } catch (IllegalArgumentException ignored) {
                // a non-UUID run_id is rejected further along, by the ordinary validation
            }
        }
    }

    /**
     * Protocol v2: the worker sends the run's run_id; the session (the tools' domain context — the
     * prompt's channel) is resolved on this side from the run's row. An empty or unknown run_id → null
     * (the tool is outside a channel).
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
            if (logEntry.getDetachedAt() != null) {
                // First, before every other branch: once detached the result belongs to the trigger
                // delivery, and a replayed poll must record the same interim — never the result, and
                // never CANCELLED.
                builder.setStatus(ToolResultStatus.TOOL_RESULT_STATUS_DETACHED);
            } else if (logEntry.getFinishAt() == null && cancelRequested(request.getRunId())) {
                // Stops the wait, not the call: the tool runs on and records its outcome.
                builder.setStatus(ToolResultStatus.TOOL_RESULT_STATUS_CANCELLED);
            } else if (logEntry.getFinishAt() == null) {
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

    @Override
    public void detachTool(DetachToolRequest request, StreamObserver<DetachToolResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            touchRun(request.getRunId());
            if (request.getToolCallId().isEmpty()) {
                throw Status.INVALID_ARGUMENT.withDescription("tool_call_id is required").asRuntimeException();
            }
            ToolCallLog logEntry = toolCallLogService.detach(agentId, request.getToolCallId());

            DetachToolResponse.Builder builder = DetachToolResponse.newBuilder();
            if (logEntry.getDetachedAt() != null) {
                builder.setStatus(ToolResultStatus.TOOL_RESULT_STATUS_DETACHED);
            } else if (logEntry.getError() != null) {
                // Finished before the stamp landed — the lost race comes back as the plain result.
                builder.setStatus(ToolResultStatus.TOOL_RESULT_STATUS_ERROR)
                        .setError(logEntry.getError());
            } else {
                builder.setStatus(ToolResultStatus.TOOL_RESULT_STATUS_SUCCESS);
                if (logEntry.getOutput() != null) {
                    builder.setOutputJson(ByteString.copyFrom(
                            logEntry.getOutput().getBytes(StandardCharsets.UTF_8)));
                }
            }
            log.info("ToolGateway.DetachTool pool={} agent={} toolCallId={} -> {}",
                    poolId, agentId, request.getToolCallId(), builder.getStatus());
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver, "DetachTool pool=" + poolId
                    + " toolCallId=" + request.getToolCallId());
        }
    }

}
