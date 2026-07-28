package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.dto.IntegrationValidationResult;
import ru.agimate.controlapi.connectors.integrations.mcp.McpConnectorService;
import ru.agimate.controlapi.connectors.integrations.mcp.McpToolDiscoveryService;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.controller.manage.dto.ConnectionAgentResponse;
import ru.agimate.controlapi.controller.manage.dto.ConnectionResponse;
import ru.agimate.controlapi.controller.manage.dto.ConnectionTestResponse;
import ru.agimate.controlapi.controller.manage.dto.ConnectorJobResponse;
import ru.agimate.controlapi.controller.manage.dto.CreateConnectionRequest;
import ru.agimate.controlapi.controller.manage.dto.TriggerSpecificationResponse;
import ru.agimate.controlapi.controller.manage.dto.UpdateConnectionRequest;
import ru.agimate.controlapi.controller.manage.dto.UpdateConnectionSecretRequest;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.ConnectionTool;
import ru.agimate.controlapi.service.ConnectorJobManageService;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;
import ru.agimate.controlapi.service.connection.ConnectionService;
import ru.agimate.controlapi.service.tool.ToolDefinitionService;
import ru.agimate.controlapi.service.trigger.TriggerDefinitionService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageConnectionController.PATH)
@RequiredArgsConstructor
@Tag(name = "Connections", description = "Manage connector connections (instances)")
public class ManageConnectionController {

    public static final String PATH = "/manage/connections";

    private final ConnectionService connectionService;
    private final ConnectionBindingService bindingService;
    private final ToolDefinitionService toolDefinitionService;
    private final TriggerDefinitionService triggerDefinitionService;
    private final ConnectorJobManageService connectorJobManageService;
    private final McpToolDiscoveryService mcpToolDiscoveryService;

    @Operation(summary = "List the user's connections, filtered by connector code / enabled")
    @GetMapping("/")
    public SuccessResponse<List<ConnectionResponse>> list(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(required = false) String connectorCode,
            @RequestParam(required = false) Boolean enabled
    ) {
        UUID userId = UUID.fromString(principal.id());
        var connections = connectionService.list(userId, connectorCode, enabled).stream()
                .map(ConnectionResponse::from)
                .toList();
        return SuccessResponse.ok(connections);
    }

    @Operation(summary = "Create a new connection (requires credentials; integration connectors only)")
    @PostMapping("/")
    public SuccessResponse<ConnectionResponse> create(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateConnectionRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        Connection connection = connectionService.create(
                userId, request.connectorCode(), request.credentials(), request.name());
        return SuccessResponse.ok(ConnectionResponse.from(connection));
    }

    @Operation(summary = "Get connection details")
    @GetMapping("/{connectionId}")
    public SuccessResponse<ConnectionResponse> get(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectionId
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(ConnectionResponse.from(connectionService.getOwnedConnection(connectionId, userId)));
    }

    @Operation(summary = "Update connection settings (enable/disable, name)")
    @PatchMapping("/{connectionId}")
    public SuccessResponse<ConnectionResponse> update(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectionId,
            @Valid @RequestBody UpdateConnectionRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        Connection connection = connectionService.update(connectionId, userId, request.enabled(), request.name());
        return SuccessResponse.ok(ConnectionResponse.from(connection));
    }

    @Operation(summary = "Update connection secret (credential values)")
    @PutMapping("/{connectionId}/secret")
    public SuccessResponse<ConnectionResponse> updateSecret(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectionId,
            @Valid @RequestBody UpdateConnectionSecretRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        Connection connection = connectionService.updateSecret(connectionId, userId, request.credentials());
        return SuccessResponse.ok(ConnectionResponse.from(connection));
    }

    @Operation(summary = "Delete a connection")
    @DeleteMapping("/{connectionId}")
    public SuccessResponse<Void> delete(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectionId
    ) {
        UUID userId = UUID.fromString(principal.id());
        connectionService.delete(connectionId, userId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "List tools of a connection via SPI "
            + "(MCP — from discovered cache, static connectors — their tool set)")
    @GetMapping("/{connectionId}/tools/")
    public SuccessResponse<List<ConnectorToolSpec>> listTools(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectionId
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(
                toolDefinitionService.getConnectionTools(userId, connectionId).values().stream().toList());
    }

    @Operation(summary = "List triggers of a connection: type-declared specs merged with "
            + "dynamic instance triggers (device-apps)")
    @GetMapping("/{connectionId}/triggers/")
    public SuccessResponse<List<TriggerSpecificationResponse>> listTriggers(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectionId
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(triggerDefinitionService.getConnectionTriggers(userId, connectionId));
    }

    @Operation(summary = "List background jobs materialized for this connection (connector_jobs rows). "
            + "Lifecycle actions (pause/resume/run-now/delete) live on /manage/connector-jobs/{id}")
    @GetMapping("/{connectionId}/jobs/")
    public SuccessResponse<List<ConnectorJobResponse>> listJobs(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectionId
    ) {
        UUID userId = UUID.fromString(principal.id());
        connectionService.getOwnedConnection(connectionId, userId); // a 404 when the connection is someone else's or missing
        return SuccessResponse.ok(connectorJobManageService.getConnectionJobs(userId, connectionId));
    }

    @Operation(summary = "List agents this connection is bound to (who uses this instance). "
            + "Includes disabled agents — it is a usage inventory, not the trigger recipient list")
    @GetMapping("/{connectionId}/agents/")
    public SuccessResponse<List<ConnectionAgentResponse>> listAgents(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectionId
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(bindingService.listForConnection(userId, connectionId).stream()
                .map(ConnectionAgentResponse::from)
                .toList());
    }

    @Operation(summary = "Test a connection: validate credentials (all types) and reload tools (MCP)")
    @PostMapping("/{connectionId}/test")
    public SuccessResponse<ConnectionTestResponse> test(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectionId
    ) {
        UUID userId = UUID.fromString(principal.id());
        Connection connection = connectionService.getOwnedConnection(connectionId, userId);
        IntegrationValidationResult validation = connectionService.validate(connectionId, userId);

        // For dynamic connectors (MCP) with valid credentials we rebuild the tool cache synchronously — a
        // tools/list error is returned as a separate field rather than failing the test itself.
        Integer toolsDiscovered = null;
        String toolsError = null;
        if (validation.valid() && McpConnectorService.CONNECTOR_CODE.equals(connection.getConnectorCode())) {
            try {
                List<ConnectionTool> fresh = mcpToolDiscoveryService.discover(connectionId);
                if (fresh != null) {
                    mcpToolDiscoveryService.reconcile(connectionId, fresh);
                    toolsDiscovered = fresh.size();
                }
            } catch (ConnectorException e) {
                toolsError = e.getMessage();
            }
        }
        return SuccessResponse.ok(ConnectionTestResponse.from(validation, toolsDiscovered, toolsError));
    }
}
