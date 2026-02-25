package ru.agimate.deviceapi.service.dto;

public record ConnectedDevice(
        String connectorPubId,
        String name,
        String description
) {
}
