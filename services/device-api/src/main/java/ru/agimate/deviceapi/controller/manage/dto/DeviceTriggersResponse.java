package ru.agimate.deviceapi.controller.manage.dto;

import ru.agimate.deviceapi.service.dto.DeviceTrigger;

import java.util.List;

public record DeviceTriggersResponse(
        String connectorPubId,
        String deviceId,
        String deviceName,
        List<DeviceTrigger> triggers
) {
}
