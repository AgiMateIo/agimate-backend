package ru.agimate.common.s2s;

public record ConnectedDevice(
        String id, // pubId
        String name,
        String description
) {
}
