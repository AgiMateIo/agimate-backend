package ru.agimate.deviceapi.controller.device.dto;

import java.util.Map;

public record ToolResultRequest(
        String id,
        String name,
        Map<String, Object> result
) {
}
