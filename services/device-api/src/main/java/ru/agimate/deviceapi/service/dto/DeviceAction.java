package ru.agimate.deviceapi.service.dto;

import java.util.Map;

public record DeviceAction(
        String name,
        String description,
        Map<String, String> params
) {
}
