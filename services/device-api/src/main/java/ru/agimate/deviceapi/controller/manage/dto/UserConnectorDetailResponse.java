package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.Connector;

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

        @Schema(description = "Device features (deviceName, deviceOs, etc.)")
        Map<String, Object> deviceFeatures,

        @Schema(description = "Whether a device is connected to this connector")
        boolean connected,

        @Schema(description = "Device triggers")
        Map<String, Object> triggers,

        @Schema(description = "Device tools")
        Map<String, Object> tools
) {
    public static UserConnectorDetailResponse from(Connector connector) {
        var features = connector.getDeviceFeatures();
        var deviceName = features != null && features.get("deviceName") != null
                ? features.get("deviceName").toString() : null;
        var deviceOs = features != null && features.get("deviceOs") != null
                ? features.get("deviceOs").toString() : null;

        return new UserConnectorDetailResponse(
                connector.getPubId(),
                connector.getName(),
                connector.getDeviceId(),
                deviceName,
                deviceOs,
                features,
                connector.isLinked(),
                connector.getTriggers(),
                connector.getTools()
        );
    }
}
