package ru.agimate.mobileapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.mobileapi.controller.dto.request.CreateDeviceAuthKeyRequest;
import ru.agimate.mobileapi.controller.dto.request.UpdateDeviceAuthKeyRequest;
import ru.agimate.mobileapi.controller.dto.response.DeviceAuthKeyCreatedResponse;
import ru.agimate.mobileapi.controller.dto.response.DeviceAuthKeyResponse;
import ru.agimate.mobileapi.database.entities.DeviceAuthKey;
import ru.agimate.mobileapi.security.MobileUserPrincipal;
import ru.agimate.mobileapi.service.DeviceAuthKeyService;
import ru.agimate.mobileapi.service.dto.DeviceAuthKeyCreateResult;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@Tag(name = "Device Auth Keys", description = "Manage API keys for mobile device connections")
public class UserController {

    private final DeviceAuthKeyService deviceAuthKeyService;

    @Operation(summary = "Get all device auth keys for the current user")
    @GetMapping("/connections")
    public SuccessResponse<List<DeviceAuthKeyResponse>> getConnections(
            @AuthenticationPrincipal MobileUserPrincipal principal
    ) {
        UUID userPubId = UUID.fromString(principal.getPubId());
        List<DeviceAuthKey> keys = deviceAuthKeyService.getKeysForUser(userPubId);
        List<DeviceAuthKeyResponse> response = keys.stream()
                .map(DeviceAuthKeyResponse::from)
                .toList();
        return SuccessResponse.ok(response);
    }

    @Operation(summary = "Create a new device auth key",
               description = "Creates a new API key. The key value is shown ONLY ONCE in the response. Store it securely.")
    @PostMapping("/connections")
    public SuccessResponse<DeviceAuthKeyCreatedResponse> createConnection(
            @AuthenticationPrincipal MobileUserPrincipal principal,
            @Valid @RequestBody CreateDeviceAuthKeyRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.getPubId());
        DeviceAuthKeyCreateResult result = deviceAuthKeyService.createKey(
                userPubId,
                request.name(),
                request.description()
        );
        return SuccessResponse.ok(DeviceAuthKeyCreatedResponse.from(
                result.deviceAuthKey(),
                result.plaintextKey()
        ));
    }

    @Operation(summary = "Get a specific device auth key")
    @GetMapping("/connections/{connectionId}")
    public SuccessResponse<DeviceAuthKeyResponse> getConnection(
            @AuthenticationPrincipal MobileUserPrincipal principal,
            @PathVariable UUID connectionId
    ) {
        UUID userPubId = UUID.fromString(principal.getPubId());
        DeviceAuthKey key = deviceAuthKeyService.getKeyByPubId(connectionId, userPubId)
                .orElseThrow(() -> new NotFoundStatusException("Device auth key not found"));
        return SuccessResponse.ok(DeviceAuthKeyResponse.from(key));
    }

    @Operation(summary = "Update a device auth key")
    @PutMapping("/connections/{connectionId}")
    public SuccessResponse<DeviceAuthKeyResponse> updateConnection(
            @AuthenticationPrincipal MobileUserPrincipal principal,
            @PathVariable UUID connectionId,
            @Valid @RequestBody UpdateDeviceAuthKeyRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.getPubId());
        DeviceAuthKey updated = deviceAuthKeyService.updateKey(
                connectionId,
                userPubId,
                request.name(),
                request.description(),
                request.enabled()
        );
        return SuccessResponse.ok(DeviceAuthKeyResponse.from(updated));
    }

    @Operation(summary = "Delete a device auth key (soft delete)")
    @DeleteMapping("/connections/{connectionId}")
    public SuccessResponse<Void> deleteConnection(
            @AuthenticationPrincipal MobileUserPrincipal principal,
            @PathVariable UUID connectionId
    ) {
        UUID userPubId = UUID.fromString(principal.getPubId());
        deviceAuthKeyService.deleteKey(connectionId, userPubId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Regenerate a device auth key",
               description = "Invalidates the old key and creates a new one with the same settings")
    @PostMapping("/connections/{connectionId}/regenerate")
    public SuccessResponse<DeviceAuthKeyCreatedResponse> regenerateConnection(
            @AuthenticationPrincipal MobileUserPrincipal principal,
            @PathVariable UUID connectionId
    ) {
        UUID userPubId = UUID.fromString(principal.getPubId());
        DeviceAuthKeyCreateResult result = deviceAuthKeyService.regenerateKey(connectionId, userPubId);
        return SuccessResponse.ok(DeviceAuthKeyCreatedResponse.from(
                result.deviceAuthKey(),
                result.plaintextKey()
        ));
    }
}
