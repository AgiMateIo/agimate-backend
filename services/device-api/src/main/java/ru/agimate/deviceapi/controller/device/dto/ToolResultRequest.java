package ru.agimate.deviceapi.controller.device.dto;

import ru.agimate.deviceapi.service.IToolResult;

import java.util.Map;

public record ToolResultRequest(
        String id,
        String name,
        Map<String, Object> result
) implements IToolResult {

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getResult() {
        return result;
    }
}
