package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.App;

import java.util.Map;
import java.util.UUID;

@Schema(description = "Full connector information including device features, triggers and tools")
public record UserConnectorDetailResponse(
        @Schema(description = "Connector public ID")
        UUID connectorId,

        @Schema(description = "Connector name")
        String connectorName,

        @Schema(description = "Device ID")
        String deviceId,

        @Schema(description = "Device name")
        String deviceName,

        @Schema(description = "Device OS")
        String deviceOs,

        @Schema(description = "Device info (deviceName, deviceOs, etc.)")
        Map<String, Object> info,

        @Schema(description = "Whether a device is connected to this connector")
        boolean connected,

        @Schema(description = "Device triggers")
        Map<String, Object> triggers,

        @Schema(description = "Device tools")
        Map<String, Object> tools
) {
    public static UserConnectorDetailResponse from(App app) {
        var info = app.getInfo();
        var deviceName = info != null && info.get("deviceName") != null
                ? info.get("deviceName").toString() : null;
        var deviceOs = info != null && info.get("deviceOs") != null
                ? info.get("deviceOs").toString() : null;

        return new UserConnectorDetailResponse(
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
