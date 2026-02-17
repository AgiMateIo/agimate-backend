package ru.agimate.deviceapi.controller.device.dto;

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
        Map<String, Object> tools
) {
}
