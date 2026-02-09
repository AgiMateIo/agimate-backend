package ru.agimate.deviceapi.controller.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record LinkDeviceRequest(
        @NotNull
        String deviceId,
        @NotNull
        String deviceName,
        @NotNull
        String deviceOs,
        Map<String, Object> triggers,
        Map<String, Object> actions
) {
}
