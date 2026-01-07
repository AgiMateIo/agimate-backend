package ru.agimate.connectorsapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.connectorsapi.controller.manage.dto.request.CreateConnectorsApiKeyRequest;
import ru.agimate.connectorsapi.controller.manage.dto.request.UpdateConnectorsApiKeyRequest;
import ru.agimate.connectorsapi.controller.manage.dto.response.ConnectorsApiKeyCreateResponse;
import ru.agimate.connectorsapi.controller.manage.dto.response.ConnectorsApiKeyResponse;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.connectorsapi.service.ConnectorsApiKeyService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ConnectorsApiKeyController.PATH)
@RequiredArgsConstructor
@Tag(name = "Connectors Api Keys", description = "Manage API keys for connector access")
public class ConnectorsApiKeyController {

    public static final String PATH = "/api-keys";

    private final ConnectorsApiKeyService connectorsApiKeyService;

    @Operation(summary = "Get all api keys for current user")
    @GetMapping
    public SuccessResponse<List<ConnectorsApiKeyResponse>> getAuthKeys() {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(
                connectorsApiKeyService.getKeysForUser(userPubId).stream()
                        .map(ConnectorsApiKeyResponse::from)
                        .toList()
        );
    }

    @Operation(summary = "Create new api key")
    @PostMapping
    public SuccessResponse<ConnectorsApiKeyCreateResponse> createAuthKey(
            @Valid @RequestBody CreateConnectorsApiKeyRequest request
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        var result = connectorsApiKeyService.createKey(userPubId, request.name(), request.description());
        return SuccessResponse.ok(new ConnectorsApiKeyCreateResponse(
                ConnectorsApiKeyResponse.from(result.connectorsApiKey()),
                result.fullKey()
        ));
    }

    @Operation(summary = "Update auth key")
    @PutMapping("/{keyId}")
    public SuccessResponse<ConnectorsApiKeyResponse> updateAuthKey(
            @PathVariable UUID keyId,
            @Valid @RequestBody UpdateConnectorsApiKeyRequest request
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        var updated = connectorsApiKeyService.updateKey(
                keyId, userPubId, request.name(), request.description(), request.enabled()
        );
        return SuccessResponse.ok(ConnectorsApiKeyResponse.from(updated));
    }

    @Operation(summary = "Delete auth key")
    @DeleteMapping("/{keyId}")
    public SuccessResponse<Void> deleteAuthKey(@PathVariable UUID keyId) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        connectorsApiKeyService.deleteKey(keyId, userPubId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Regenerate auth key")
    @PostMapping("/{keyId}/regenerate")
    public SuccessResponse<ConnectorsApiKeyCreateResponse> regenerateAuthKey(@PathVariable UUID keyId) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        var result = connectorsApiKeyService.regenerateKey(keyId, userPubId);
        return SuccessResponse.ok(new ConnectorsApiKeyCreateResponse(
                ConnectorsApiKeyResponse.from(result.connectorsApiKey()),
                result.fullKey()
        ));
    }
}
