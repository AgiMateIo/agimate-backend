package ru.agimate.deviceapi.service.centrifugo;

public record CentrifugoMessage<T>(
        String type,
        T payload
) {
}
