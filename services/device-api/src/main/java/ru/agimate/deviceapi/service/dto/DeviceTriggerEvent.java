package ru.agimate.deviceapi.service.dto;

import ru.agimate.deviceapi.controller.dto.request.TriggerRequest;
import ru.agimate.deviceapi.database.entities.DeviceAuthKey;

public record DeviceTriggerEvent(
        DeviceAuthKey deviceAuthKey,
        TriggerRequest triggerRequest
) {}
