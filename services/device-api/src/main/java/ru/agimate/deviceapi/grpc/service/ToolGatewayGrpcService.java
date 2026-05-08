package ru.agimate.deviceapi.grpc.service;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.controller.agent.dto.ToolUseRequest;
import ru.agimate.deviceapi.grpc.auth.WorkerPoolContextHolder;
import ru.agimate.deviceapi.service.AgentToolUseService;
import ru.agimate.worker.v1.ExecuteToolAsyncAck;
import ru.agimate.worker.v1.ExecuteToolBatchRequest;
import ru.agimate.worker.v1.ExecuteToolBatchResponse;
import ru.agimate.worker.v1.ExecuteToolRequest;
import ru.agimate.worker.v1.ExecuteToolResponse;
import ru.agimate.worker.v1.ToolEvent;
import ru.agimate.worker.v1.ToolGatewayGrpc;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ToolGatewayGrpcService extends ToolGatewayGrpc.ToolGatewayImplBase {

    private final AgentToolUseService agentToolUseService;

    @Override
    public void executeTool(ExecuteToolRequest request, StreamObserver<ExecuteToolResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            ToolUseRequest toolUse = buildToolUseRequest(request);
            String toolUseId = agentToolUseService.processToolUse(agentId, toolUse);
            log.info("ToolGateway.ExecuteTool ok pool={} agent={} workflow={} tool={} toolUseId={}",
                    poolId, agentId, request.getWorkflowId(), request.getToolName(), toolUseId);
            ExecuteToolResponse response = ExecuteToolResponse.newBuilder()
                    .setSuccess(true)
                    .setResultJson(ByteString.copyFrom(("{\"tool_use_id\":\"" + toolUseId + "\"}")
                            .getBytes(StandardCharsets.UTF_8)))
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (ForbiddenStatusException e) {
            log.info("ToolGateway.ExecuteTool denied pool={} workflow={} tool={} reason={}",
                    poolId, request.getWorkflowId(), request.getToolName(), e.getMessage());
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription(e.getMessage()).asRuntimeException());
        } catch (NotFoundStatusException e) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage()).asRuntimeException());
        } catch (ConflictStatusException e) {
            responseObserver.onError(Status.ABORTED
                    .withDescription(e.getMessage()).asRuntimeException());
        } catch (io.grpc.StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("ToolGateway.ExecuteTool failed pool={} workflow={}",
                    poolId, request.getWorkflowId(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void executeToolStream(ExecuteToolRequest request, StreamObserver<ToolEvent> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.asRuntimeException());
    }

    @Override
    public void executeToolBatch(ExecuteToolBatchRequest request, StreamObserver<ExecuteToolBatchResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.asRuntimeException());
    }

    @Override
    public void executeToolAsync(ExecuteToolRequest request, StreamObserver<ExecuteToolAsyncAck> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED.asRuntimeException());
    }

    private static ToolUseRequest buildToolUseRequest(ExecuteToolRequest request) {
        if (request.getToolCallId().isEmpty()) {
            throw Status.INVALID_ARGUMENT.withDescription("tool_call_id is required").asRuntimeException();
        }
        if (request.getConnectorCode().isEmpty()) {
            throw Status.INVALID_ARGUMENT.withDescription("connector_code is required").asRuntimeException();
        }
        if (request.getToolName().isEmpty()) {
            throw Status.INVALID_ARGUMENT.withDescription("tool_name is required").asRuntimeException();
        }

        Map<String, Object> input = Map.of();
        if (!request.getArgsJson().isEmpty()) {
            String json = request.getArgsJson().toStringUtf8();
            input = JsonUtils.readValue(json, JsonUtils.MAP_TYPE_REFERENCE);
        }

        return ToolUseRequest.builder()
                .id(request.getToolCallId())
                .connectorCode(request.getConnectorCode())
                .identity(emptyToNull(request.getIdentity()))
                .name(request.getToolName())
                .input(input)
                .agentSessionId(emptyToNull(request.getAgentSessionId()))
                .build();
    }

    private static UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw Status.INVALID_ARGUMENT
                    .withDescription(field + " is required").asRuntimeException();
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw Status.INVALID_ARGUMENT
                    .withDescription(field + " is not a valid UUID").asRuntimeException();
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
