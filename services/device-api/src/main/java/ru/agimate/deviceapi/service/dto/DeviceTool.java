package ru.agimate.deviceapi.service.dto;

import java.util.List;

public record DeviceTool(
        String name,
        List<String> params
) {
}
