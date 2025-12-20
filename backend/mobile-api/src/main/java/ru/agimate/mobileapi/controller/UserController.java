package ru.agimate.mobileapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.mobileapi.controller.dto.request.CreateConnectionKeyRequest;
import ru.agimate.mobileapi.controller.dto.request.UpdateConnectionKeyRequest;
import ru.agimate.mobileapi.controller.dto.response.ConnectionKeyCreatedResponse;
import ru.agimate.mobileapi.controller.dto.response.ConnectionKeyResponse;
import ru.agimate.mobileapi.database.entities.ConnectionKey;
import ru.agimate.mobileapi.security.MobileUserPrincipal;
import ru.agimate.mobileapi.service.ConnectionKeyService;
import ru.agimate.mobileapi.service.dto.ConnectionKeyCreateResult;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@Tag(name = "Connection Keys", description = "Manage API keys for mobile device connections")
public class UserController {

    private final ConnectionKeyService connectionKeyService;

    @Operation(summary = "Get all connection keys for the current user")
    @GetMapping("/connections")
    public SuccessResponse<List<ConnectionKeyResponse>> getConnections(
            @AuthenticationPrincipal MobileUserPrincipal principal
    ) {
        UUID userPubId = UUID.fromString(principal.getPubId());
        List<ConnectionKey> keys = connectionKeyService.getKeysForUser(userPubId);
        List<ConnectionKeyResponse> response = keys.stream()
                .map(ConnectionKeyResponse::from)
                .toList();
        return SuccessResponse.ok(response);
    }

    @Operation(summary = "Create a new connection key",
               description = "Creates a new API key. The key value is shown ONLY ONCE in the response. Store it securely.")
    @PostMapping("/connections")
    public SuccessResponse<ConnectionKeyCreatedResponse> createConnection(
            @AuthenticationPrincipal MobileUserPrincipal principal,
            @Valid @RequestBody CreateConnectionKeyRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.getPubId());
        ConnectionKeyCreateResult result = connectionKeyService.createKey(
                userPubId,
                request.name(),
                request.description()
        );
        return SuccessResponse.ok(ConnectionKeyCreatedResponse.from(
                result.connectionKey(),
                result.plaintextKey()
        ));
    }

    @Operation(summary = "Get a specific connection key")
    @GetMapping("/connections/{connectionId}")
    public SuccessResponse<ConnectionKeyResponse> getConnection(
            @AuthenticationPrincipal MobileUserPrincipal principal,
            @PathVariable UUID connectionId
    ) {
        UUID userPubId = UUID.fromString(principal.getPubId());
        ConnectionKey key = connectionKeyService.getKeyByPubId(connectionId, userPubId)
                .orElseThrow(() -> new ru.agimate.common.rest.error.NotFoundStatusException("Connection key not found"));
        return SuccessResponse.ok(ConnectionKeyResponse.from(key));
    }

    @Operation(summary = "Update a connection key")
    @PutMapping("/connections/{connectionId}")
    public SuccessResponse<ConnectionKeyResponse> updateConnection(
            @AuthenticationPrincipal MobileUserPrincipal principal,
            @PathVariable UUID connectionId,
            @Valid @RequestBody UpdateConnectionKeyRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.getPubId());
        ConnectionKey updated = connectionKeyService.updateKey(
                connectionId,
                userPubId,
                request.name(),
                request.description(),
                request.enabled()
        );
        return SuccessResponse.ok(ConnectionKeyResponse.from(updated));
    }

    @Operation(summary = "Delete a connection key (soft delete)")
    @DeleteMapping("/connections/{connectionId}")
    public SuccessResponse<Void> deleteConnection(
            @AuthenticationPrincipal MobileUserPrincipal principal,
            @PathVariable UUID connectionId
    ) {
        UUID userPubId = UUID.fromString(principal.getPubId());
        connectionKeyService.deleteKey(connectionId, userPubId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Regenerate a connection key",
               description = "Invalidates the old key and creates a new one with the same settings")
    @PostMapping("/connections/{connectionId}/regenerate")
    public SuccessResponse<ConnectionKeyCreatedResponse> regenerateConnection(
            @AuthenticationPrincipal MobileUserPrincipal principal,
            @PathVariable UUID connectionId
    ) {
        UUID userPubId = UUID.fromString(principal.getPubId());
        ConnectionKeyCreateResult result = connectionKeyService.regenerateKey(connectionId, userPubId);
        return SuccessResponse.ok(ConnectionKeyCreatedResponse.from(
                result.connectionKey(),
                result.plaintextKey()
        ));
    }
}
