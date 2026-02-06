package ru.agimate.deviceapi.controller.dto.request;

import jakarta.validation.constraints.NotNull;

public record DeviceChannelTokenRequest(
        @NotNull
        String deviceId
) {
}
