package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.Device;
import ru.agimate.deviceapi.database.entities.DeviceAuthKey;

import java.util.UUID;

@Schema(description = "User device connection information")
public record UserDeviceResponse(
        @Schema(description = "DeviceAuthKey public ID")
        UUID deviceAuthKeyId,

        @Schema(description = "DeviceAuthKey name")
        String deviceAuthKeyName,

        @Schema(description = "Linked device ID")
        String linkedDeviceId,

        @Schema(description = "Device name")
        String deviceName,

        @Schema(description = "Device OS")
        String deviceOs,

        @Schema(description = "Whether a device is connected to this auth key")
        boolean connected
) {
    public static UserDeviceResponse from(DeviceAuthKey authKey) {
        Device device = authKey.getDevice();
        return new UserDeviceResponse(
                authKey.getPubId(),
                authKey.getName(),
                device != null ? device.getDeviceId() : null,
                device != null ? device.getName() : null,
                device != null ? device.getOs() : null,
                device != null
        );
    }
}
