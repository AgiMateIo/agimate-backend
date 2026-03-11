package ru.agimate.deviceapi.controller.manage.dto;

import ru.agimate.deviceapi.service.dto.AppTool;

import java.util.List;

public record DeviceToolsResponse(
        String connectorPubId,
        String deviceId,
        String deviceName,
        List<AppTool> tools
) {
}
