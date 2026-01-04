package ru.agimate.connectorsapi.controller.api.dto;

public record ConnectorShorInfoResponse(
        String name,
        String description,
        String code
) {
}
