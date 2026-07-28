package ru.agimate.controlapi.service.dto;

/**
 * The service-layer result of a tool call — for internal producers (executing connectors), which
 * cannot reach the HTTP DTOs ({@code controller/**}) given the direction of the layers.
 */
public record ToolResult(
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
