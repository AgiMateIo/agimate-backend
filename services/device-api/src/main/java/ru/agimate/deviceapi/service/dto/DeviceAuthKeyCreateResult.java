package ru.agimate.deviceapi.service.dto;

import ru.agimate.deviceapi.database.entities.DeviceAuthKey;

public record DeviceAuthKeyCreateResult(
        DeviceAuthKey deviceAuthKey,
        String plaintextKey
) {}
