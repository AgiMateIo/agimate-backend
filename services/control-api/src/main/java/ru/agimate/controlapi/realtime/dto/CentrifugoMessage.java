package ru.agimate.controlapi.realtime.dto;

public record CentrifugoMessage<T>(
        String type,
        T payload
) {
}
