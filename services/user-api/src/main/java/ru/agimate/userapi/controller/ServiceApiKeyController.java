package ru.agimate.userapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.userapi.controller.dto.request.CreateServiceApiKeyRequest;
import ru.agimate.userapi.controller.dto.request.UpdateServiceApiKeyRequest;
import ru.agimate.userapi.controller.dto.response.ServiceApiKeyCreateResponse;
import ru.agimate.userapi.controller.dto.response.ServiceApiKeyResponse;
import ru.agimate.userapi.service.ServiceApiKeyService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ServiceApiKeyController.PATH)
@RequiredArgsConstructor
@Tag(name = "Service Api Keys", description = "Manage API keys for connector access")
public class ServiceApiKeyController {

    public static final String PATH = "/manage/api-keys";

    private final ServiceApiKeyService serviceApiKeyService;

    @Operation(summary = "Get all api keys for current user")
    @GetMapping("/")
    public SuccessResponse<List<ServiceApiKeyResponse>> getApiKeys() {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(
                serviceApiKeyService.getKeysForUser(userPubId).stream()
                        .map(ServiceApiKeyResponse::from)
                        .toList()
        );
    }

    @Operation(summary = "Create new api key")
    @PostMapping("/")
    public SuccessResponse<ServiceApiKeyCreateResponse> createApiKey(
            @Valid @RequestBody CreateServiceApiKeyRequest request
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        var result = serviceApiKeyService.createKey(userPubId, request.name(), request.description());
        return SuccessResponse.ok(new ServiceApiKeyCreateResponse(
                ServiceApiKeyResponse.from(result.serviceApiKey()),
                result.fullKey()
        ));
    }

    @Operation(summary = "Update api key")
    @PutMapping("/{keyId}")
    public SuccessResponse<ServiceApiKeyResponse> updateApiKey(
            @PathVariable UUID keyId,
            @Valid @RequestBody UpdateServiceApiKeyRequest request
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        var updated = serviceApiKeyService.updateKey(
                keyId, userPubId, request.name(), request.description(), request.enabled()
        );
        return SuccessResponse.ok(ServiceApiKeyResponse.from(updated));
    }

    @Operation(summary = "Delete api key")
    @DeleteMapping("/{keyId}")
    public SuccessResponse<Void> deleteApiKey(@PathVariable UUID keyId) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        serviceApiKeyService.deleteKey(keyId, userPubId);
        return SuccessResponse.empty();
    }
}
