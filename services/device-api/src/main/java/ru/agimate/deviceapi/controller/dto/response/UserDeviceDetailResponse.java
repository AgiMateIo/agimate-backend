package ru.agimate.deviceapi.controller.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.Device;
import ru.agimate.deviceapi.database.entities.DeviceAuthKey;

import java.util.Map;
import java.util.UUID;

@Schema(description = "Full device information including triggers and actions")
public record UserDeviceDetailResponse(
        @Schema(description = "Device ID")
        String deviceId,

        @Schema(description = "Device name")
        String deviceName,

        @Schema(description = "Device OS")
        String deviceOs,

        @Schema(description = "DeviceAuthKey public ID")
        UUID deviceAuthKeyId,

        @Schema(description = "DeviceAuthKey name")
        String deviceAuthKeyName,

        @Schema(description = "Whether a device is connected to an auth key")
        boolean connected,

        @Schema(description = "Device triggers")
        Map<String, Object> triggers,

        @Schema(description = "Device actions")
        Map<String, Object> actions
) {
    public static UserDeviceDetailResponse from(Device device) {
        DeviceAuthKey authKey = device.getDeviceAuthKey();
        return new UserDeviceDetailResponse(
                device.getDeviceId(),
                device.getName(),
                device.getOs(),
                authKey != null ? authKey.getPubId() : null,
                authKey != null ? authKey.getName() : null,
                authKey != null,
                device.getTriggers(),
                device.getActions()
        );
    }
}
