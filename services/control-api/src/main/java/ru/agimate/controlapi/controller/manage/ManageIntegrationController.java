package ru.agimate.controlapi.controller.manage;

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
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.connectors.integrations.IntegrationService;
import ru.agimate.controlapi.connectors.integrations.IntegrationValidationResult;
import ru.agimate.controlapi.connectors.integrations.mcp.McpConnectorService;
import ru.agimate.controlapi.connectors.integrations.mcp.McpToolService;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.controller.manage.dto.IntegrationTestResponse;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.ConnectionTool;
import ru.agimate.controlapi.controller.manage.dto.CreateIntegrationRequest;
import ru.agimate.controlapi.controller.manage.dto.IntegrationResponse;
import ru.agimate.controlapi.controller.manage.dto.TriggerSpecificationResponse;
import ru.agimate.controlapi.controller.manage.dto.UpdateIntegrationCredentialsRequest;
import ru.agimate.controlapi.controller.manage.dto.UpdateIntegrationRequest;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.enums.ConnectorType;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageIntegrationController.PATH)
@RequiredArgsConstructor
@Tag(name = "Integrations", description = "Manage user integration credentials")
public class ManageIntegrationController {

    public static final String PATH = "/manage/integrations";

    private final IntegrationService integrationService;
    private final ConnectorRegistry connectorRegistry;
    private final ConnectorRepository connectorRepository;
    private final McpToolService mcpToolService;

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
    public SuccessResponse<List<ConnectorToolSpec>> listTools(@RequestParam String connectorCode) {
        IntegrationConnectorHandler handler = loadIntegrationHandler(connectorCode);
        return SuccessResponse.ok(handler.getTools().values().stream().toList());
    }

    @Operation(summary = "List tools of a specific integration instance via SPI "
            + "(MCP — from discovered cache, static connectors — their tool set)")
    @GetMapping("/credentials/{credentialId}/tools/")
    public SuccessResponse<List<ConnectorToolSpec>> listInstanceTools(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID credentialId
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(integrationService.getInstanceTools(credentialId, userId));
    }

    @Operation(summary = "Test an integration: validate credentials (all types) and reload tools (MCP)")
    @PostMapping("/credentials/{credentialId}/test")
    public SuccessResponse<IntegrationTestResponse> testCredentials(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID credentialId
    ) {
        UUID userId = UUID.fromString(principal.id());
        Connection connection = integrationService.getIntegrationCredentials(credentialId, userId);
        IntegrationValidationResult validation = integrationService.validateExisting(credentialId, userId);

        // Для динамических коннекторов (MCP) при валидных credentials пересобираем кэш тулов
        // синхронно — ошибку tools/list возвращаем отдельным полем, не роняя сам тест.
        Integer toolsDiscovered = null;
        String toolsError = null;
        if (validation.valid() && McpConnectorService.CONNECTOR_CODE.equals(connection.getConnectorCode())) {
            try {
                List<ConnectionTool> fresh = mcpToolService.discover(credentialId);
                if (fresh != null) {
                    mcpToolService.reconcile(credentialId, fresh);
                    toolsDiscovered = fresh.size();
                }
            } catch (ConnectorException e) {
                toolsError = e.getMessage();
            }
        }
        return SuccessResponse.ok(IntegrationTestResponse.from(validation, toolsDiscovered, toolsError));
    }

    @Operation(summary = "List predefined triggers exposed by an integration connector")
    @GetMapping("/triggers/")
    public SuccessResponse<List<TriggerSpecificationResponse>> listTriggers(@RequestParam String connectorCode) {
        IntegrationConnectorHandler handler = loadIntegrationHandler(connectorCode);
        List<TriggerSpecificationResponse> triggers = handler.getTriggers().entrySet().stream()
                .map(entry -> TriggerSpecificationResponse.from(entry.getKey(), entry.getValue()))
                .toList();
        return SuccessResponse.ok(triggers);
    }

    private IntegrationConnectorHandler loadIntegrationHandler(String connectorCode) {
        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));
        if (connector.getType() != ConnectorType.INTEGRATION) {
            throw new BadRequestStatusException(
                    "Connector " + connectorCode + " is not an INTEGRATION type (got " + connector.getType() + ")");
        }
        return connectorRegistry.findIntegrationHandler(connectorCode)
                .orElseThrow(() -> new BadRequestStatusException("Unsupported platform: " + connectorCode));
    }
}
