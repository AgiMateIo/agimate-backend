package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.dto.request.CreateDeviceAuthKeyRequest;
import ru.agimate.deviceapi.controller.dto.request.UpdateDeviceAuthKeyRequest;
import ru.agimate.deviceapi.controller.dto.response.DeviceAuthKeyCreatedResponse;
import ru.agimate.deviceapi.controller.dto.response.DeviceAuthKeyResponse;
import ru.agimate.deviceapi.database.entities.DeviceAuthKey;
import ru.agimate.deviceapi.service.DeviceAuthKeyService;
import ru.agimate.deviceapi.service.dto.DeviceAuthKeyCreateResult;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageDeviceKeysController.PATH)
@RequiredArgsConstructor
@Tag(name = "Device Auth Keys", description = "Manage device connections")
public class ManageDeviceKeysController {

    public static final String PATH = "/manage/device-keys";

    private final DeviceAuthKeyService deviceAuthKeyService;

    @Operation(summary = "Get all device auth keys for the current user")
    @GetMapping("/")
    public SuccessResponse<List<DeviceAuthKeyResponse>> getConnections(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        List<DeviceAuthKey> keys = deviceAuthKeyService.getKeysForUser(userPubId);
        List<DeviceAuthKeyResponse> response = keys.stream()
                .map(DeviceAuthKeyResponse::from)
                .toList();
        return SuccessResponse.ok(response);
    }

    @Operation(summary = "Create a new device auth key",
               description = "Creates a new API key. The key value is shown ONLY ONCE in the response. Store it securely.")
    @PostMapping("/")
    public SuccessResponse<DeviceAuthKeyCreatedResponse> createConnection(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateDeviceAuthKeyRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
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
    @GetMapping("/{connectionId}")
    public SuccessResponse<DeviceAuthKeyResponse> getConnection(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectionId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        DeviceAuthKey key = deviceAuthKeyService.getKeyByPubId(connectionId, userPubId)
                .orElseThrow(() -> new NotFoundStatusException("Device auth key not found"));
        return SuccessResponse.ok(DeviceAuthKeyResponse.from(key));
    }

    @Operation(summary = "Update a device auth key")
    @PutMapping("/{connectionId}")
    public SuccessResponse<DeviceAuthKeyResponse> updateConnection(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectionId,
            @Valid @RequestBody UpdateDeviceAuthKeyRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
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
    @DeleteMapping("/{connectionId}")
    public SuccessResponse<Void> deleteConnection(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectionId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        deviceAuthKeyService.deleteKey(connectionId, userPubId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Regenerate a device auth key",
               description = "Invalidates the old key and creates a new one with the same settings")
    @PostMapping("/{connectionId}/regenerate")
    public SuccessResponse<DeviceAuthKeyCreatedResponse> regenerateConnection(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectionId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        DeviceAuthKeyCreateResult result = deviceAuthKeyService.regenerateKey(connectionId, userPubId);
        return SuccessResponse.ok(DeviceAuthKeyCreatedResponse.from(
                result.deviceAuthKey(),
                result.plaintextKey()
        ));
    }
}
