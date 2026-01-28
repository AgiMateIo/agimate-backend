package ru.agimate.mobileapi.service.dto;

import ru.agimate.mobileapi.controller.dto.request.TriggerRequest;
import ru.agimate.mobileapi.database.entities.DeviceAuthKey;

public record DeviceTriggerEvent(
        DeviceAuthKey deviceAuthKey,
        TriggerRequest triggerRequest
) {}
