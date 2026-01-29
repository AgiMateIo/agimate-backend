package ru.agimate.mobileapi.controller.dto.request;

import jakarta.validation.constraints.NotNull;

public record LinkDeviceRequest(
        @NotNull
        String deviceId,
        @NotNull
        String deviceName,
        @NotNull
        String deviceOs
) {
}
