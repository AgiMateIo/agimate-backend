package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.App;

import java.util.UUID;

@Schema(description = "User connector connection information")
public record UserConnectorResponse(
        @Schema(description = "Connector public ID")
        UUID connectorId,

        @Schema(description = "Connector name")
        String connectorName,

        @Schema(description = "Linked device ID")
        String linkedDeviceId,

        @Schema(description = "Device name")
        String deviceName,

        @Schema(description = "Device OS")
        String deviceOs,

        @Schema(description = "Whether a device is connected to this connector")
        boolean connected
) {
    @SuppressWarnings("unchecked")
    public static UserConnectorResponse from(App app) {
        String deviceName = null;
        String deviceOs = null;
        if (app.getInfo() != null) {
            Object name = app.getInfo().get("deviceName");
            if (name != null) deviceName = name.toString();
            Object os = app.getInfo().get("deviceOs");
            if (os != null) deviceOs = os.toString();
        }
        return new UserConnectorResponse(
                app.getPubId(),
                app.getName(),
                app.getDeviceId(),
                deviceName,
                deviceOs,
                app.isLinked()
        );
    }
}
