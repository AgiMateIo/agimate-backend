package ru.agimate.deviceapi.controller.device.dto;

import jakarta.validation.constraints.NotNull;

public record DeviceChannelTokenRequest(
        @NotNull
        String deviceId
) {
}
