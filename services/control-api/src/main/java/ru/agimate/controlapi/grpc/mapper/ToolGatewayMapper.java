package ru.agimate.controlapi.grpc.mapper;

import io.grpc.Status;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.ExecuteToolRequest;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;

import java.util.Map;

import static ru.agimate.controlapi.grpc.support.GrpcSupport.emptyToNull;

/** Mapping of proto {@link ExecuteToolRequest} → the domain {@link ToolCallRequest}. */
@Slf4j
@UtilityClass
public class ToolGatewayMapper {

    public static ToolCallRequest toToolCallRequest(ExecuteToolRequest request, String agentSessionId) {
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
        if (!request.getInput().isEmpty()) {
            try {
                input = JsonUtils.readValue(request.getInput().toStringUtf8(), JsonUtils.MAP_TYPE_REFERENCE);
            } catch (RuntimeException e) {
                // The arguments are the caller's, so this is INVALID_ARGUMENT rather than the INTERNAL
                // a bare RuntimeException would map to. The description crosses to the worker — no payload
                // in it; the reason is logged here because handleError passes a Status through unlogged.
                log.warn("ExecuteTool input for tool '{}' is not a JSON object: {}",
                        request.getToolName(), e.getMessage());
                throw Status.INVALID_ARGUMENT.withDescription("input is not a JSON object").asRuntimeException();
            }
        }

        return ToolCallRequest.builder()
                .id(request.getToolCallId())
                .connectorCode(request.getConnectorCode())
                .connectionId(emptyToNull(request.getConnectionId()))
                .name(request.getToolName())
                .input(input)
                .agentSessionId(agentSessionId)
                .runId(emptyToNull(request.getRunId()))
                .build();
    }
}
