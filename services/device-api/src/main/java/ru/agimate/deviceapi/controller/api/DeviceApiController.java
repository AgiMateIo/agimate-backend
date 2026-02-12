package ru.agimate.deviceapi.controller.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.deviceapi.controller.dto.response.DeviceTriggersResponse;
import ru.agimate.deviceapi.service.dto.ConnectedDevice;
import ru.agimate.deviceapi.service.dto.DeviceAction;
import ru.agimate.deviceapi.service.dto.DeviceTrigger;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.deviceapi.service.InternalDeviceApiService;

import java.util.List;

@RestController
@RequestMapping(DeviceApiController.PATH)
@RequiredArgsConstructor
@Tag(name = "Device API", description = "Device operations via API Key")
public class DeviceApiController {

    public static final String PATH = "/api/device";

    private final InternalDeviceApiService internalDeviceApiService;

    @Operation(
            summary = "Get connected devices",
            description = "Returns all connected devices for the authenticated user"
    )
    @GetMapping("/")
    public SuccessResponse<List<ConnectedDevice>> getDevices() {
        var userPubId = SecurityUtils.getApiKeyUserPubId();
        var devices = internalDeviceApiService.getDevices(userPubId.toString());
        return SuccessResponse.ok(devices);
    }

    @Operation(
            summary = "Get all device triggers",
            description = "Returns available triggers for all user's devices"
    )
    @GetMapping("/triggers/")
    public SuccessResponse<List<DeviceTriggersResponse>> getAllTriggers() {
        var userPubId = SecurityUtils.getApiKeyUserPubId();
        var triggers = internalDeviceApiService.getAllTriggers(userPubId.toString());
        return SuccessResponse.ok(triggers);
    }

    @Operation(
            summary = "Get device triggers",
            description = "Returns available triggers for a specific device"
    )
    @GetMapping("/triggers/{deviceId}")
    public SuccessResponse<List<DeviceTrigger>> getTriggers(@PathVariable String deviceId) {
        var triggers = internalDeviceApiService.getTriggers(deviceId);
        return SuccessResponse.ok(triggers);
    }

    @Operation(
            summary = "Get device actions",
            description = "Returns available actions for a specific device"
    )
    @GetMapping("/actions/{deviceId}")
    public SuccessResponse<List<DeviceAction>> getActions(@PathVariable String deviceId) {
        var actions = internalDeviceApiService.getActions(deviceId);
        return SuccessResponse.ok(actions);
    }
}
