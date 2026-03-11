package ru.agimate.deviceapi.service.dto;

import java.util.List;

public record AppTool(
        String name,
        String description,
        List<String> params
) {
}
