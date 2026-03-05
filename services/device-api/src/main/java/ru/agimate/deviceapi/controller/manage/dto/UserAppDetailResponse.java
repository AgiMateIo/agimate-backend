package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.App;

import java.util.Map;
import java.util.UUID;

@Schema(description = "Full app information including device features, triggers and tools")
public record UserAppDetailResponse(
        @Schema(description = "App public ID")
        UUID appId,

        @Schema(description = "App name")
        String appName,

        @Schema(description = "Device ID")
        String deviceId,

        @Schema(description = "Device name")
        String deviceName,

        @Schema(description = "Device OS")
        String deviceOs,

        @Schema(description = "Device info (deviceName, deviceOs, etc.)")
        Map<String, Object> info,

        @Schema(description = "Whether a device is connected to this app")
        boolean connected,

        @Schema(description = "Device triggers")
        Map<String, Object> triggers,

        @Schema(description = "Device tools")
        Map<String, Object> tools
) {
    public static UserAppDetailResponse from(App app) {
        var info = app.getInfo();
        var deviceName = info != null && info.get("deviceName") != null
                ? info.get("deviceName").toString() : null;
        var deviceOs = info != null && info.get("deviceOs") != null
                ? info.get("deviceOs").toString() : null;

        return new UserAppDetailResponse(
                app.getPubId(),
                app.getName(),
                app.getDeviceId(),
                deviceName,
                deviceOs,
                info,
                app.isLinked(),
                app.getTriggers(),
                app.getTools()
        );
    }
}
