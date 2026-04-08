package ru.agimate.deviceapi.service.dto;

public record CentrifugoMessage(
        String type,
        Object payload
) {
}
