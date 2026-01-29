package ru.agimate.mobileapi.controller.dto.request;

import jakarta.validation.constraints.NotNull;

public record DeviceChannelTokenRequest(
        @NotNull
        String deviceId
) {
}
