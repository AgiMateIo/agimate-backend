package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.connectors.integrations.IntegrationService;
import ru.agimate.deviceapi.controller.manage.dto.CreateIntegrationRequest;
import ru.agimate.deviceapi.controller.manage.dto.IntegrationResponse;
import ru.agimate.deviceapi.controller.manage.dto.UpdateIntegrationCredentialsRequest;
import ru.agimate.deviceapi.controller.manage.dto.UpdateIntegrationRequest;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageIntegrationController.PATH)
@RequiredArgsConstructor
@Tag(name = "Integrations", description = "Manage user integration credentials")
public class ManageIntegrationController {

    public static final String PATH = "/manage/integrations";

    private final IntegrationService integrationService;

    @Operation(summary = "List integration credentials, optionally filtered by connectorCode")
    @GetMapping("/credentials/")
    public SuccessResponse<List<IntegrationResponse>> listCredentials(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(required = false) String connectorCode
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var integrations = integrationService.getIntegrations(userPubId, connectorCode).stream()
                .map(IntegrationResponse::from)
                .toList();
        return SuccessResponse.ok(integrations);
    }

    @Operation(summary = "Create new integration credentials")
    @PostMapping("/credentials/")
    public SuccessResponse<IntegrationResponse> createCredentials(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateIntegrationRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var integrationCredentials = integrationService.createIntegration(
                userPubId, request.connectorCode(), request.credentials(), request.name());
        return SuccessResponse.ok(IntegrationResponse.from(integrationCredentials));
    }

    @Operation(summary = "Get integration credentials details")
    @GetMapping("/credentials/{credentialId}")
    public SuccessResponse<IntegrationResponse> getCredentials(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID credentialId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var integrationCredentials = integrationService.getIntegrationCredentials(credentialId, userPubId);
        return SuccessResponse.ok(IntegrationResponse.from(integrationCredentials));
    }

    @Operation(summary = "Update integration settings (enable/disable, name)")
    @PatchMapping("/credentials/{credentialId}/")
    public SuccessResponse<IntegrationResponse> updateCredentials(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID credentialId,
            @Valid @RequestBody UpdateIntegrationRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var integrationCredentials = integrationService.patchIntegration(credentialId, userPubId, request.enabled(), request.name());
        return SuccessResponse.ok(IntegrationResponse.from(integrationCredentials));
    }

    @Operation(summary = "Update integration secret (credential values)")
    @PutMapping("/credentials/{credentialId}/secret")
    public SuccessResponse<IntegrationResponse> updateSecret(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID credentialId,
            @Valid @RequestBody UpdateIntegrationCredentialsRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var integrationCredentials = integrationService.updateCredentials(credentialId, userPubId, request.credentials());
        return SuccessResponse.ok(IntegrationResponse.from(integrationCredentials));
    }

    @Operation(summary = "Delete integration credentials")
    @DeleteMapping("/credentials/{credentialId}")
    public SuccessResponse<Void> deleteCredentials(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID credentialId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        integrationService.deleteIntegration(credentialId, userPubId);
        return SuccessResponse.empty();
    }
}
