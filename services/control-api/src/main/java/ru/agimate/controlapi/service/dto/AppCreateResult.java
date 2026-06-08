package ru.agimate.controlapi.service.dto;

import ru.agimate.controlapi.database.entities.App;

public record AppCreateResult(
        App app,
        String plaintextKey
) {}
