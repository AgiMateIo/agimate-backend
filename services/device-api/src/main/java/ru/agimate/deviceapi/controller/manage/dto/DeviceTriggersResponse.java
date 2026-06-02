package ru.agimate.deviceapi.controller.manage.dto;

import ru.agimate.deviceapi.service.dto.AppTrigger;

import java.util.List;

public record DeviceTriggersResponse(
        String connectorId,
        String deviceId,
        String deviceName,
        List<AppTrigger> triggers
) {
}
