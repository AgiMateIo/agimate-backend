package ru.agimate.mobileapi.service.dto;

import ru.agimate.mobileapi.database.entities.ConnectionKey;

public record ConnectionKeyCreateResult(
        ConnectionKey connectionKey,
        String plaintextKey
) {}
