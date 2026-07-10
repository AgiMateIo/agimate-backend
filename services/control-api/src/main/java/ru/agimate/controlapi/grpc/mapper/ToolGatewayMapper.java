package ru.agimate.controlapi.grpc.mapper;

import io.grpc.Status;
import lombok.experimental.UtilityClass;
import ru.agimate.agentworker.ExecuteToolRequest;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;

import java.util.Map;

import static ru.agimate.controlapi.grpc.support.GrpcSupport.emptyToNull;

/** Маппинг proto {@link ExecuteToolRequest} → доменный {@link ToolCallRequest}. */
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
            String json = request.getInput().toStringUtf8();
            input = JsonUtils.readValue(json, JsonUtils.MAP_TYPE_REFERENCE);
        }

        return ToolCallRequest.builder()
                .id(request.getToolCallId())
                .connectorCode(request.getConnectorCode())
                .connectionId(emptyToNull(request.getConnectionId()))
                .name(request.getToolName())
                .input(input)
                .agentSessionId(agentSessionId)
                .build();
    }
}
