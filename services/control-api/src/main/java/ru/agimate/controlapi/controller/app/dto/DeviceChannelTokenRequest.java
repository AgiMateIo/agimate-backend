package ru.agimate.controlapi.controller.app.dto;

import jakarta.validation.constraints.NotNull;

public record DeviceChannelTokenRequest(
        @NotNull
        String deviceId
) {
}
