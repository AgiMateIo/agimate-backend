package ru.agimate.connectorsapi.controller.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.s2s.ConnectedDevice;
import ru.agimate.common.s2s.DeviceAction;
import ru.agimate.common.s2s.DeviceTrigger;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.connectorsapi.service.MobileApiService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(MobileCallController.PATH)
@RequiredArgsConstructor
@Tag(name = "Mobile Call", description = "Execute mobile device methods via API Key")
public class MobileCallController {

    public static final String PATH = "/api/call/mobile";

    private final MobileApiService mobileApiService;

    @Operation(
            summary = "Get connected devices",
            description = "Returns all connected mobile devices for the authenticated user"
    )
    @GetMapping("/devices")
    public SuccessResponse<List<ConnectedDevice>> getDevices() {
        var userPubId = SecurityUtils.getApiKeyUserPubId();
        var devices = mobileApiService.getDevices(userPubId.toString());
        return SuccessResponse.ok(devices);
    }

    @Operation(
            summary = "Get device triggers",
            description = "Returns available triggers for a specific mobile device"
    )
    @GetMapping("/{deviceId}/triggers")
    public SuccessResponse<List<DeviceTrigger>> getTriggers(@PathVariable String deviceId) {
        var triggers = mobileApiService.getTriggers(deviceId);
        return SuccessResponse.ok(triggers);
    }

    @Operation(
            summary = "Get device actions",
            description = "Returns available actions for a specific mobile device"
    )
    @GetMapping("/{deviceId}/actions")
    public SuccessResponse<List<DeviceAction>> getActions(@PathVariable String deviceId) {
        var actions = mobileApiService.getActions(deviceId);
        return SuccessResponse.ok(actions);
    }

    @Operation(
            summary = "Push action to device",
            description = "Sends an action to a specific mobile device via Centrifugo"
    )
    @PostMapping("/{deviceId}/action")
    public SuccessResponse<Void> pushAction(
            @PathVariable String deviceId,
            @Valid @RequestBody Map<String, Object> actionData
    ) {
        mobileApiService.pushAction(deviceId, actionData);
        return SuccessResponse.ok(null);
    }
}
