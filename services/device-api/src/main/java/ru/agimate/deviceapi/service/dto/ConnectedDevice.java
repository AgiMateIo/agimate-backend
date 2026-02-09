package ru.agimate.deviceapi.service.dto;

public record ConnectedDevice(
        String deviceAuthKeyId, // pubId
        String name,
        String description
) {
}
