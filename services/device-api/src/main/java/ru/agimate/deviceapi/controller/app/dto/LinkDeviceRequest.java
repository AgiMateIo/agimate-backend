package ru.agimate.deviceapi.controller.app.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record LinkDeviceRequest(
        @NotNull
        String deviceId,
        Map<String, Object> deviceFeatures,
        Map<String, Object> triggers,
        Map<String, Object> tools
) {
}
