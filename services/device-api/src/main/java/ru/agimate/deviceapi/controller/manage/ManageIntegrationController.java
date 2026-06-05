package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.connectors.integrations.IntegrationHandler;
import ru.agimate.deviceapi.connectors.integrations.IntegrationService;
import ru.agimate.deviceapi.connectors.integrations.IntegrationsRegistry;
import ru.agimate.deviceapi.controller.agent.dto.ToolSpecificationMapper;
import ru.agimate.deviceapi.controller.agent.dto.ToolSpecificationResponse;
import ru.agimate.deviceapi.controller.manage.dto.CreateIntegrationRequest;
import ru.agimate.deviceapi.controller.manage.dto.IntegrationResponse;
import ru.agimate.deviceapi.controller.manage.dto.TriggerSpecificationResponse;
import ru.agimate.deviceapi.controller.manage.dto.UpdateIntegrationCredentialsRequest;
import ru.agimate.deviceapi.controller.manage.dto.UpdateIntegrationRequest;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.database.enums.ConnectorType;
import ru.agimate.deviceapi.database.repositories.ConnectorRepository;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageIntegrationController.PATH)
@RequiredArgsConstructor
@Tag(name = "Integrations", description = "Manage user integration credentials")
public class ManageIntegrationController {

    public static final String PATH = "/manage/integrations";

    private final IntegrationService integrationService;
    private final IntegrationsRegistry integrationsRegistry;
    private final ConnectorRepository connectorRepository;

    @Operation(summary = "List integration credentials, optionally filtered by connectorCode")
    @GetMapping("/credentials/")
    public SuccessResponse<List<IntegrationResponse>> listCredentials(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(required = false) String connectorCode
    ) {
        UUID userId = UUID.fromString(principal.id());
        var integrations = integrationService.getIntegrations(userId, connectorCode).stream()
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
        UUID userId = UUID.fromString(principal.id());
        var integrationCredentials = integrationService.createIntegration(
                userId, request.connectorCode(), request.credentials(), request.name());
        return SuccessResponse.ok(IntegrationResponse.from(integrationCredentials));
    }

    @Operation(summary = "Get integration credentials details")
    @GetMapping("/credentials/{credentialId}")
    public SuccessResponse<IntegrationResponse> getCredentials(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID credentialId
    ) {
        UUID userId = UUID.fromString(principal.id());
        var integrationCredentials = integrationService.getIntegrationCredentials(credentialId, userId);
        return SuccessResponse.ok(IntegrationResponse.from(integrationCredentials));
    }

    @Operation(summary = "Update integration settings (enable/disable, name)")
    @PatchMapping("/credentials/{credentialId}/")
    public SuccessResponse<IntegrationResponse> updateCredentials(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID credentialId,
            @Valid @RequestBody UpdateIntegrationRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        var integrationCredentials = integrationService.patchIntegration(credentialId, userId, request.enabled(), request.name());
        return SuccessResponse.ok(IntegrationResponse.from(integrationCredentials));
    }

    @Operation(summary = "Update integration secret (credential values)")
    @PutMapping("/credentials/{credentialId}/secret")
    public SuccessResponse<IntegrationResponse> updateSecret(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID credentialId,
            @Valid @RequestBody UpdateIntegrationCredentialsRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        var integrationCredentials = integrationService.updateCredentials(credentialId, userId, request.credentials());
        return SuccessResponse.ok(IntegrationResponse.from(integrationCredentials));
    }

    @Operation(summary = "Delete integration credentials")
    @DeleteMapping("/credentials/{credentialId}")
    public SuccessResponse<Void> deleteCredentials(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID credentialId
    ) {
        UUID userId = UUID.fromString(principal.id());
        integrationService.deleteIntegration(credentialId, userId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "List predefined tools exposed by an integration connector")
    @GetMapping("/tools/")
    public SuccessResponse<List<ToolSpecificationResponse>> listTools(@RequestParam String connectorCode) {
        IntegrationHandler handler = loadIntegrationHandler(connectorCode);
        List<ToolSpecificationResponse> tools = handler.getPredefinedTools().values().stream()
                .map(ToolSpecificationMapper::toResponse)
                .toList();
        return SuccessResponse.ok(tools);
    }

    @Operation(summary = "List predefined triggers exposed by an integration connector")
    @GetMapping("/triggers/")
    public SuccessResponse<List<TriggerSpecificationResponse>> listTriggers(@RequestParam String connectorCode) {
        IntegrationHandler handler = loadIntegrationHandler(connectorCode);
        List<TriggerSpecificationResponse> triggers = handler.getPredefinedTriggers().entrySet().stream()
                .map(entry -> TriggerSpecificationResponse.from(entry.getKey(), entry.getValue()))
                .toList();
        return SuccessResponse.ok(triggers);
    }

    private IntegrationHandler loadIntegrationHandler(String connectorCode) {
        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));
        if (connector.getType() != ConnectorType.INTEGRATION) {
            throw new BadRequestStatusException(
                    "Connector " + connectorCode + " is not an INTEGRATION type (got " + connector.getType() + ")");
        }
        return integrationsRegistry.getHandler(connectorCode);
    }
}
