package ru.agimate.controlapi.controller.manage.dto;

import ru.agimate.controlapi.service.dto.AppTrigger;

import java.util.List;

public record DeviceTriggersResponse(
        String connectorId,
        String deviceId,
        String deviceName,
        List<AppTrigger> triggers
) {
}
