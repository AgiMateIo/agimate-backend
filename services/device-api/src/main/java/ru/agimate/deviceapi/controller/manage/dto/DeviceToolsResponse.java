package ru.agimate.deviceapi.controller.manage.dto;

import ru.agimate.deviceapi.service.dto.DeviceTool;

import java.util.List;

public record DeviceToolsResponse(
        String deviceAuthKeyId,
        String deviceId,
        String deviceName,
        List<DeviceTool> tools
) {
}
