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
import ru.agimate.deviceapi.service.AppApiService;

import java.util.List;

@RestController
@RequestMapping(ApiAppsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Apps API", description = "App operations via API Key")
public class ApiAppsController {

    public static final String PATH = "/api/apps";

    private final AppApiService appApiService;

    @Operation(
            summary = "Get connected apps",
            description = "Returns all connected apps for the authenticated user"
    )
    @GetMapping("/")
    public SuccessResponse<List<ConnectedDevice>> getApps() {
        var userPubId = SecurityUtils.getApiKeyUserPubId();
        var devices = appApiService.getApps(userPubId.toString());
        return SuccessResponse.ok(devices);
    }

    @Operation(
            summary = "Get all app triggers",
            description = "Returns available triggers for all user's apps"
    )
    @GetMapping("/triggers/")
    public SuccessResponse<List<DeviceTriggersResponse>> getAllTriggers() {
        var userPubId = SecurityUtils.getApiKeyUserPubId();
        var triggers = appApiService.getAllAppTriggers(userPubId);
        return SuccessResponse.ok(triggers);
    }

    @Operation(
            summary = "Get app triggers",
            description = "Returns available triggers for a specific app"
    )
    @GetMapping("/triggers/{appId}")
    public SuccessResponse<List<DeviceTrigger>> getTriggers(@PathVariable String appId) {
        var triggers = appApiService.getTriggers(appId);
        return SuccessResponse.ok(triggers);
    }


    @GetMapping("/tools/")
    public SuccessResponse<List<DeviceTriggersResponse>> getAllTools() {
        // TODO: implement as for triggers
        return SuccessResponse.ok(null);
    }

    @Operation(
            summary = "Get app tools",
            description = "Returns available tools for a specific app"
    )
    @GetMapping("/tools/{appId}")
    public SuccessResponse<List<DeviceTool>> getTools(@PathVariable String appId) {
        var tools = appApiService.getTools(appId);
        return SuccessResponse.ok(tools);
    }
}
