package ru.agimate.controlapi.service.dto;

/**
 * Сервисный результат tool-вызова — для внутренних производителей (исполнение коннекторов),
 * которым HTTP-DTO ({@code controller/**}) недоступны по направлению слоёв.
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
