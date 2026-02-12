package ru.agimate.deviceapi.controller.dto.response;

import ru.agimate.deviceapi.service.dto.DeviceTrigger;

import java.util.List;

public record DeviceTriggersResponse(
        String deviceAuthKeyId,
        String deviceId,
        String deviceName,
        List<DeviceTrigger> triggers
) {
}
