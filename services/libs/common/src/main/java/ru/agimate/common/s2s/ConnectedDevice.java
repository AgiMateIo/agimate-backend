package ru.agimate.common.s2s;

public record ConnectedDevice(
        String deviceAuthKeyId, // pubId
        String name,
        String description
) {
}
