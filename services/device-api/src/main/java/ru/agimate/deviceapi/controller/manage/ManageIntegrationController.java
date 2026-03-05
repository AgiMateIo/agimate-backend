package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.manage.dto.CreateIntegrationRequest;
import ru.agimate.deviceapi.controller.manage.dto.IntegrationPlatformInfo;
import ru.agimate.deviceapi.controller.manage.dto.IntegrationResponse;
import ru.agimate.deviceapi.controller.manage.dto.UpdateIntegrationCredentialsRequest;
import ru.agimate.deviceapi.controller.manage.dto.UpdateIntegrationRequest;
import ru.agimate.deviceapi.connectors.integrations.IntegrationPlatformRegistry;
import ru.agimate.deviceapi.connectors.integrations.IntegrationService;
import ru.agimate.deviceapi.database.entities.IntegrationCredentials;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageIntegrationController.PATH)
@RequiredArgsConstructor
@Tag(name = "Integrations", description = "Manage platform integrations")
public class ManageIntegrationController {

    public static final String PATH = "/manage/integrations";

    private final IntegrationService integrationService;
    private final IntegrationPlatformRegistry platformRegistry;

    @Operation(summary = "Get available integration platforms")
    @GetMapping("/platforms/")
    public SuccessResponse<List<IntegrationPlatformInfo>> getPlatforms() {
        var platforms = platformRegistry.getAvailablePlatforms().stream()
                .map(IntegrationPlatformInfo::from)
                .toList();
        return SuccessResponse.ok(platforms);
    }

    @Operation(summary = "Get all integrations for the current user")
    @GetMapping("/")
    public SuccessResponse<List<IntegrationResponse>> getIntegrations(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var integrations = integrationService.getIntegrations(userPubId).stream()
                .map(this::toResponse)
                .toList();
        return SuccessResponse.ok(integrations);
    }

    @Operation(summary = "Create a new integration")
    @PostMapping("/")
    public SuccessResponse<IntegrationResponse> createIntegration(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateIntegrationRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var integrationCredentials = integrationService.createIntegration(
                userPubId, request.platformCode(), request.credentials(), request.name());
        return SuccessResponse.ok(toResponse(integrationCredentials));
    }

    @Operation(summary = "Get integration details")
    @GetMapping("/{id}")
    public SuccessResponse<IntegrationResponse> getIntegration(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var integrationCredentials = integrationService.getIntegration(id, userPubId);
        return SuccessResponse.ok(toResponse(integrationCredentials));
    }

    @Operation(summary = "Update integration credentials")
    @PutMapping("/{id}/credentials")
    public SuccessResponse<IntegrationResponse> updateCredentials(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateIntegrationCredentialsRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var integrationCredentials = integrationService.updateCredentials(id, userPubId, request.credentials());
        return SuccessResponse.ok(toResponse(integrationCredentials));
    }

    @Operation(summary = "Update integration settings (enable/disable, name)")
    @PatchMapping("/{id}")
    public SuccessResponse<IntegrationResponse> updateIntegration(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateIntegrationRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var integrationCredentials = integrationService.updateIntegration(id, userPubId, request.enabled(), request.name());
        return SuccessResponse.ok(toResponse(integrationCredentials));
    }

    @Operation(summary = "Delete an integration")
    @DeleteMapping("/{id}")
    public SuccessResponse<Void> deleteIntegration(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        integrationService.deleteIntegration(id, userPubId);
        return SuccessResponse.empty();
    }

    private IntegrationResponse toResponse(IntegrationCredentials ic) {
        var handler = platformRegistry.getHandler(ic.extractPlatformCode());
        return IntegrationResponse.from(ic, handler);
    }
}
