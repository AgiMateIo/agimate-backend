package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.App;

import java.util.UUID;

@Schema(description = "User app connection information")
public record UserAppResponse(
        @Schema(description = "App public ID")
        UUID appId,

        @Schema(description = "App name")
        String appName,

        @Schema(description = "Linked device ID")
        String linkedDeviceId,

        @Schema(description = "Device name")
        String deviceName,

        @Schema(description = "Device OS")
        String deviceOs,

        @Schema(description = "Whether a device is connected to this app")
        boolean connected
) {
    @SuppressWarnings("unchecked")
    public static UserAppResponse from(App app) {
        String deviceName = null;
        String deviceOs = null;
        if (app.getInfo() != null) {
            Object name = app.getInfo().get("deviceName");
            if (name != null) deviceName = name.toString();
            Object os = app.getInfo().get("deviceOs");
            if (os != null) deviceOs = os.toString();
        }
        return new UserAppResponse(
                app.getPubId(),
                app.getName(),
                app.getDeviceId(),
                deviceName,
                deviceOs,
                app.isLinked()
        );
    }
}
