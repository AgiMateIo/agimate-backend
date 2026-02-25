package ru.agimate.deviceapi.service.dto;

import ru.agimate.deviceapi.database.entities.Connector;

public record ConnectorCreateResult(
        Connector connector,
        String plaintextKey
) {}
