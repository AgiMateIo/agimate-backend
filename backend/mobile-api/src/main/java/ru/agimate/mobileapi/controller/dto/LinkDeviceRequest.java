package ru.agimate.mobileapi.controller.dto;

public record LinkDeviceRequest(
        String deviceId,
        String deviceName,
        String deviceOs
) {
}
