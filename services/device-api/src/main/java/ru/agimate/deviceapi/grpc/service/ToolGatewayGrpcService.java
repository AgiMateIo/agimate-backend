package ru.agimate.deviceapi.grpc.service;

import com.google.protobuf.ByteString;
import dev.langchain4j.agent.tool.ToolSpecification;
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
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.connectors.integrations.IntegrationsRegistry;
import ru.agimate.deviceapi.connectors.internal.ServerSideToolRegistry;
import ru.agimate.deviceapi.controller.agent.dto.ToolSpecificationMapper;
import ru.agimate.deviceapi.controller.agent.dto.ToolSpecificationResponse;
import ru.agimate.deviceapi.controller.agent.dto.ToolUseRequest;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.database.entities.ToolUseLog;
import ru.agimate.deviceapi.database.repositories.ConnectorRepository;
import ru.agimate.deviceapi.grpc.auth.WorkerPoolContextHolder;
import ru.agimate.deviceapi.service.AgentService;
import ru.agimate.deviceapi.service.AgentToolUseService;
import ru.agimate.deviceapi.service.dto.AgentToolSpec;
import ru.agimate.worker.v1.AgentToolDef;
import ru.agimate.worker.v1.ConnectorToolSpec;
import ru.agimate.worker.v1.ExecuteToolAsyncAck;
import ru.agimate.worker.v1.ExecuteToolRequest;
import ru.agimate.worker.v1.GetConnectorToolsRequest;
import ru.agimate.worker.v1.GetConnectorToolsResponse;
import ru.agimate.worker.v1.GetToolResultRequest;
import ru.agimate.worker.v1.GetToolResultResponse;
import ru.agimate.worker.v1.ListAgentToolsRequest;
import ru.agimate.worker.v1.ListAgentToolsResponse;
import ru.agimate.worker.v1.ToolGatewayGrpc;
import ru.agimate.worker.v1.ToolResultStatus;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ToolGatewayGrpcService extends ToolGatewayGrpc.ToolGatewayImplBase {

    private final AgentToolUseService agentToolUseService;
    private final AgentService agentService;
    private final IntegrationsRegistry integrationsRegistry;
    private final ServerSideToolRegistry serverSideToolRegistry;
    private final ConnectorRepository connectorRepository;

    @Override
    public void executeToolAsync(ExecuteToolRequest request, StreamObserver<ExecuteToolAsyncAck> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            ToolUseRequest toolUse = buildToolUseRequest(request);
            String toolUseId = agentToolUseService.processToolUse(agentId, toolUse);
            log.info("ToolGateway.ExecuteToolAsync ok pool={} agent={} workflow={} tool={} toolUseId={}",
                    poolId, agentId, request.getWorkflowId(), request.getToolName(), toolUseId);
            responseObserver.onNext(ExecuteToolAsyncAck.newBuilder()
                    .setToolCallId(toolUseId)
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

    @Override
    public void getToolResult(GetToolResultRequest request, StreamObserver<GetToolResultResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            if (request.getToolCallId().isEmpty()) {
                throw Status.INVALID_ARGUMENT.withDescription("tool_call_id is required").asRuntimeException();
            }
            ToolUseLog logEntry = agentToolUseService.getToolUseLog(agentId, request.getToolCallId());

            GetToolResultResponse.Builder builder = GetToolResultResponse.newBuilder();
            if (logEntry.getOutputAt() == null) {
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

    @Override
    public void getConnectorTools(GetConnectorToolsRequest request, StreamObserver<GetConnectorToolsResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            String connectorCode = request.getConnectorCode();
            if (connectorCode.isEmpty()) {
                throw Status.INVALID_ARGUMENT.withDescription("connector_code is required").asRuntimeException();
            }

            Connector connector = connectorRepository.findById(connectorCode)
                    .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));

            Map<String, ToolSpecification> tools = switch (connector.getType()) {
                case INTEGRATION -> integrationsRegistry.getHandler(connectorCode).getPredefinedTools();
                case INTERNAL_SERVICE -> serverSideToolRegistry.getHandler(connectorCode).getToolDefinitions();
                case APP, LOOPBACK -> throw new BadRequestStatusException(
                        "Connector type " + connector.getType() + " does not expose static tool definitions");
            };

            GetConnectorToolsResponse.Builder builder = GetConnectorToolsResponse.newBuilder();
            tools.forEach((name, spec) -> {
                ToolSpecificationResponse dto = ToolSpecificationMapper.toResponse(spec);
                ConnectorToolSpec.Builder toolBuilder = ConnectorToolSpec.newBuilder()
                        .setName(dto.name() != null ? dto.name() : name);
                if (dto.description() != null) {
                    toolBuilder.setDescription(dto.description());
                }
                if (dto.parameters() != null) {
                    String json = JsonUtils.writeValueAsString(dto.parameters());
                    toolBuilder.setParametersJsonSchema(
                            ByteString.copyFrom(json.getBytes(StandardCharsets.UTF_8)));
                }
                builder.addTools(toolBuilder.build());
            });
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
            log.error("ToolGateway.GetConnectorTools failed pool={} connector={}",
                    poolId, request.getConnectorCode(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void listAgentTools(ListAgentToolsRequest request, StreamObserver<ListAgentToolsResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            ListAgentToolsResponse.Builder builder = ListAgentToolsResponse.newBuilder();
            for (AgentToolSpec spec : agentService.getAvailableToolSpecs(agentId)) {
                AgentToolDef.Builder toolBuilder = AgentToolDef.newBuilder()
                        .setName(spec.name());
                if (spec.description() != null) {
                    toolBuilder.setDescription(spec.description());
                }
                if (spec.parametersJsonSchema() != null) {
                    String json = JsonUtils.writeValueAsString(spec.parametersJsonSchema());
                    toolBuilder.setParametersJsonSchema(
                            ByteString.copyFrom(json.getBytes(StandardCharsets.UTF_8)));
                }
                builder.addTools(toolBuilder.build());
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
            log.error("ToolGateway.ListAgentTools failed pool={} agent={}",
                    poolId, request.getAgentId(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription(e.getMessage()).asRuntimeException());
        }
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
