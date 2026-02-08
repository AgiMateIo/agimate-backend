package ru.agimate.connectorsapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.connectorsapi.controller.manage.dto.request.CreateServiceApiKeyRequest;
import ru.agimate.connectorsapi.controller.manage.dto.request.UpdateServiceApiKeyRequest;
import ru.agimate.connectorsapi.controller.manage.dto.response.ServiceApiKeyCreateResponse;
import ru.agimate.connectorsapi.controller.manage.dto.response.ServiceApiKeyResponse;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.connectorsapi.service.ServiceApiKeyService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ServiceApiKeyManageController.PATH)
@RequiredArgsConstructor
@Tag(name = "Service Api Keys", description = "Manage API keys for connector access")
public class ServiceApiKeyManageController {

    public static final String PATH = "/manage/api-keys";

    private final ServiceApiKeyService serviceApiKeyService;

    @Operation(summary = "Get all api keys for current user")
    @GetMapping("/")
    public SuccessResponse<List<ServiceApiKeyResponse>> getAuthKeys() {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(
                serviceApiKeyService.getKeysForUser(userPubId).stream()
                        .map(ServiceApiKeyResponse::from)
                        .toList()
        );
    }

    @Operation(summary = "Create new api key")
    @PostMapping("/")
    public SuccessResponse<ServiceApiKeyCreateResponse> createAuthKey(
            @Valid @RequestBody CreateServiceApiKeyRequest request
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        var result = serviceApiKeyService.createKey(userPubId, request.name(), request.description());
        return SuccessResponse.ok(new ServiceApiKeyCreateResponse(
                ServiceApiKeyResponse.from(result.serviceApiKey()),
                result.fullKey()
        ));
    }

    @Operation(summary = "Update auth key")
    @PutMapping("/{keyId}")
    public SuccessResponse<ServiceApiKeyResponse> updateAuthKey(
            @PathVariable UUID keyId,
            @Valid @RequestBody UpdateServiceApiKeyRequest request
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        var updated = serviceApiKeyService.updateKey(
                keyId, userPubId, request.name(), request.description(), request.enabled()
        );
        return SuccessResponse.ok(ServiceApiKeyResponse.from(updated));
    }

    @Operation(summary = "Delete auth key")
    @DeleteMapping("/{keyId}")
    public SuccessResponse<Void> deleteAuthKey(@PathVariable UUID keyId) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        serviceApiKeyService.deleteKey(keyId, userPubId);
        return SuccessResponse.empty();
    }

}
