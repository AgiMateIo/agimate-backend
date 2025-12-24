package ru.agimate.mobileapi.service.dto;

import ru.agimate.mobileapi.database.entities.DeviceAuthKey;

public record DeviceAuthKeyCreateResult(
        DeviceAuthKey deviceAuthKey,
        String plaintextKey
) {}
