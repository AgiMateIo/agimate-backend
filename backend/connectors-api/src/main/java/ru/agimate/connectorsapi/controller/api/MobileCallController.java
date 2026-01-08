package ru.agimate.connectorsapi.controller.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.ErrorResponse;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.s2s.ConnectedDevice;
import ru.agimate.common.s2s.DeviceAction;
import ru.agimate.common.s2s.DeviceTrigger;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.connectorsapi.controller.api.dto.mobile.MobileActionRequest;
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
            description = "Sends an action to a specific mobile device via Centrifugo",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Action successfully pushed to device",
                    content = @Content(schema = @Schema(implementation = SuccessResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request body",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid or missing API key",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @PostMapping("/{deviceId}/action")
    public SuccessResponse<Void> pushAction(
            @Parameter(
                    description = "Device identifier",
                    required = true,
                    example = "device-123"
            )
            @PathVariable String deviceId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Action request with type and parameters",
                    required = true,
                    content = @Content(schema = @Schema(implementation = MobileActionRequest.class))
            )
            @Valid @RequestBody MobileActionRequest mobileActionRequest
    ) {
        mobileApiService.pushAction(deviceId, mobileActionRequest);
        return SuccessResponse.ok(null);
    }
}
