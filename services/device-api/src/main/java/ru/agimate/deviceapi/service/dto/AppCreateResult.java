package ru.agimate.deviceapi.service.dto;

import ru.agimate.deviceapi.database.entities.App;

public record AppCreateResult(
        App app,
        String plaintextKey
) {}
