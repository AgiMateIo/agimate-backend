package ru.agimate.deviceapi.service.dto;

import ru.agimate.deviceapi.database.entities.App;

public record ConnectorCreateResult(
        App app,
        String plaintextKey
) {}
