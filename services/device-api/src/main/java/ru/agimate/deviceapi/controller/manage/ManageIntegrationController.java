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
import ru.agimate.deviceapi.controller.manage.dto.IntegrationInfo;
import ru.agimate.deviceapi.controller.manage.dto.IntegrationResponse;
import ru.agimate.deviceapi.controller.manage.dto.UpdateIntegrationCredentialsRequest;
import ru.agimate.deviceapi.controller.manage.dto.UpdateIntegrationRequest;
import ru.agimate.deviceapi.connectors.integrations.IntegrationsRegistry;
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
    private final IntegrationsRegistry integrationsRegistry;

    @Operation(summary = "Get available integration platforms")
    @GetMapping("/platforms/")
    public SuccessResponse<List<IntegrationInfo>> getPlatforms() {
        var platforms = integrationsRegistry.getAvailablePlatforms().stream()
                .map(IntegrationInfo::from)
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
                userPubId, request.connectorCode(), request.credentials(), request.name());
        return SuccessResponse.ok(toResponse(integrationCredentials));
    }

    @Operation(summary = "Get integration details")
    @GetMapping("/{integrationCredentialPubId}")
    public SuccessResponse<IntegrationResponse> getIntegration(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID integrationCredentialPubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var integrationCredentials = integrationService.getIntegrationCredentials(integrationCredentialPubId, userPubId);
        return SuccessResponse.ok(toResponse(integrationCredentials));
    }

    @Operation(summary = "Update integration credentials")
    @PutMapping("/{integrationCredentialPubId}/credentials")
    public SuccessResponse<IntegrationResponse> updateCredentials(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID integrationCredentialPubId,
            @Valid @RequestBody UpdateIntegrationCredentialsRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var integrationCredentials = integrationService.updateCredentials(integrationCredentialPubId, userPubId, request.credentials());
        return SuccessResponse.ok(toResponse(integrationCredentials));
    }

    @Operation(summary = "Update integration settings (enable/disable, name)")
    @PatchMapping("/{integrationCredentialPubId}/")
    public SuccessResponse<IntegrationResponse> updateIntegration(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID integrationCredentialPubId,
            @Valid @RequestBody UpdateIntegrationRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var integrationCredentials = integrationService.patchIntegration(integrationCredentialPubId, userPubId, request.enabled(), request.name());
        return SuccessResponse.ok(toResponse(integrationCredentials));
    }

    @Operation(summary = "Delete an integration")
    @DeleteMapping("/{integrationCredentialPubId}")
    public SuccessResponse<Void> deleteIntegration(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID integrationCredentialPubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        integrationService.deleteIntegration(integrationCredentialPubId, userPubId);
        return SuccessResponse.empty();
    }

    private IntegrationResponse toResponse(IntegrationCredentials ic) {
        var handler = integrationsRegistry.getHandler(ic.getConnectorCode());
        return IntegrationResponse.from(ic, handler);
    }
}
