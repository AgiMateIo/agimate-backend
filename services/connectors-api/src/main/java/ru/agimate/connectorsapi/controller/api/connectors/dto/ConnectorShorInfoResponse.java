package ru.agimate.connectorsapi.controller.api.connectors.dto;

public record ConnectorShorInfoResponse(
        String name,
        String description,
        String code
) {
}
