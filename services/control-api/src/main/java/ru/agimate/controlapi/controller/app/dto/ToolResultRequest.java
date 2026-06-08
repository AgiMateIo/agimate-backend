package ru.agimate.controlapi.controller.app.dto;

import ru.agimate.controlapi.service.dto.IToolResult;


public record ToolResultRequest(
        String id,
        String connectorCode,
        String output,
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
