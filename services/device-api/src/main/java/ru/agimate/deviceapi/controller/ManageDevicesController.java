package ru.agimate.deviceapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.dto.response.UserDeviceResponse;
import ru.agimate.deviceapi.service.DeviceAuthKeyService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageDevicesController.PATH)
@RequiredArgsConstructor
@Tag(name = "Devices", description = "Manage user devices")
public class ManageDevicesController {

    public static final String PATH = "/manage/devices";

    private final DeviceAuthKeyService deviceAuthKeyService;

    @Operation(
            summary = "List user devices",
            description = "Returns all device connections for the current user with connection status"
    )
    @GetMapping("/")
    public SuccessResponse<List<UserDeviceResponse>> getUserDevices(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(deviceAuthKeyService.getUserDevices(userPubId));
    }

    @Operation(
            summary = "Disconnect device",
            description = "Removes the device link from the specified connection"
    )
    @PostMapping("/{connectionId}/disconnect")
    public SuccessResponse<Void> disconnectDevice(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectionId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        deviceAuthKeyService.disconnectDevice(connectionId, userPubId);
        return SuccessResponse.ok(null);
    }
}
