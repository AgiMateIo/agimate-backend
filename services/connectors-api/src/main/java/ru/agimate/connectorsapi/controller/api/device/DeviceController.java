package ru.agimate.connectorsapi.controller.api.device;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.s2s.ConnectedDevice;
import ru.agimate.common.s2s.DeviceAction;
import ru.agimate.common.s2s.DeviceTrigger;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.connectorsapi.service.DeviceApiService;

import java.util.List;

@RestController
@RequestMapping(DeviceController.PATH)
@RequiredArgsConstructor
@Tag(name = "Device controller", description = "Devices via API Key")
public class DeviceController {

    public static final String PATH = "/api/device";

    private final DeviceApiService deviceApiService;

    @Operation(
            summary = "Get connected devices",
            description = "Returns all connected devices for the authenticated user"
    )
    @GetMapping("/")
    public SuccessResponse<List<ConnectedDevice>> getDevices() {
        var userPubId = SecurityUtils.getApiKeyUserPubId();
        var devices = deviceApiService.getDevices(userPubId.toString());
        return SuccessResponse.ok(devices);
    }

    @Operation(
            summary = "Get device triggers",
            description = "Returns available triggers for a specific device"
    )
    @GetMapping("/triggers/{deviceId}")
    public SuccessResponse<List<DeviceTrigger>> getTriggers(@PathVariable String deviceId) {
        var triggers = deviceApiService.getTriggers(deviceId);
        return SuccessResponse.ok(triggers);
    }

    @Operation(
            summary = "Get device actions",
            description = "Returns available actions for a specific device"
    )
    @GetMapping("/actions/{deviceId}")
    public SuccessResponse<List<DeviceAction>> getActions(@PathVariable String deviceId) {
        var actions = deviceApiService.getActions(deviceId);
        return SuccessResponse.ok(actions);
    }

}
