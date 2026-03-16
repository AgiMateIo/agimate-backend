package ru.agimate.deviceapi.controller.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import ru.agimate.deviceapi.service.dto.IToolResult;

@Schema(description = "Request to save tool use result from agent")
public record AgentToolResultRequest(
        String connectorCode,

        @NotNull(message = "Tool use ID is required")
        @Schema(description = "Tool use correlation ID")
        String id,

        @Schema(description = "Tool execution output")
        String output,

        @Schema(description = "Tool execution error")
        String error
) implements IToolResult {

    @Override
    public String getConnectorCode() {
        return connectorCode;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getOutput() {
        return output;
    }

    @Override
    public String getError() {
        return error;
    }
}
