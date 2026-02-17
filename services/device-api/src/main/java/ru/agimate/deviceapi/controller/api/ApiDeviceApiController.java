package ru.agimate.deviceapi.controller.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.deviceapi.controller.manage.dto.DeviceTriggersResponse;
import ru.agimate.deviceapi.service.dto.ConnectedDevice;
import ru.agimate.deviceapi.service.dto.DeviceTool;
import ru.agimate.deviceapi.service.dto.DeviceTrigger;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.deviceapi.service.DeviceApiService;

import java.util.List;

@RestController
@RequestMapping(ApiDeviceApiController.PATH)
@RequiredArgsConstructor
@Tag(name = "Device API", description = "Device operations via API Key")
public class ApiDeviceApiController {

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
            summary = "Get all device triggers",
            description = "Returns available triggers for all user's devices"
    )
    @GetMapping("/triggers/")
    public SuccessResponse<List<DeviceTriggersResponse>> getAllTriggers() {
        var userPubId = SecurityUtils.getApiKeyUserPubId();
        var triggers = deviceApiService.getAllTriggers(userPubId.toString());
        return SuccessResponse.ok(triggers);
    }

    @Operation(
            summary = "Get device triggers",
            description = "Returns available triggers for a specific device"
    )
    @GetMapping("/triggers/{deviceId}")
    public SuccessResponse<List<DeviceTrigger>> getTriggers(@PathVariable String deviceId) {
        // todo: refine - get triggers from database
        var triggers = deviceApiService.getTriggers(deviceId);
        return SuccessResponse.ok(triggers);
    }


    @GetMapping("/tools/")
    public SuccessResponse<List<DeviceTriggersResponse>> getAllTools() {
        // TODO: implement as for triggers
        return SuccessResponse.ok(null);
    }

    @Operation(
            summary = "Get device tools",
            description = "Returns available tools for a specific device"
    )
    @GetMapping("/tools/{deviceId}")
    public SuccessResponse<List<DeviceTool>> getTools(@PathVariable String deviceId) {
        var tools = deviceApiService.getTools(deviceId);
        return SuccessResponse.ok(tools);
    }
}
