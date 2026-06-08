package ru.agimate.controlapi.controller.manage.dto;

import ru.agimate.controlapi.service.dto.AppTool;

import java.util.List;

public record DeviceToolsResponse(
        String connectorId,
        String deviceId,
        String deviceName,
        List<AppTool> tools
) {
}
