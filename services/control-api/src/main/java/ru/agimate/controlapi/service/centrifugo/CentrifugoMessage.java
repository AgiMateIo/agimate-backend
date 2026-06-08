package ru.agimate.controlapi.service.centrifugo;

public record CentrifugoMessage<T>(
        String type,
        T payload
) {
}
