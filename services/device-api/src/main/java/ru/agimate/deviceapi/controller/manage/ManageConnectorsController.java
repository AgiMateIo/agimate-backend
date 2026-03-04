package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.manage.dto.*;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.service.ConnectorService;
import ru.agimate.deviceapi.service.dto.ConnectorCreateResult;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageConnectorsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Connectors", description = "Manage connectors and device connections")
public class ManageConnectorsController {

    public static final String PATH = "/manage/connectors";

    private final ConnectorService connectorService;

    @Operation(summary = "Get all connectors for the current user")
    @GetMapping("/")
    public SuccessResponse<List<ConnectorResponse>> getConnectors(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        List<App> apps = connectorService.getConnectorsForUser(userPubId);
        List<ConnectorResponse> response = apps.stream()
                .map(ConnectorResponse::from)
                .toList();
        return SuccessResponse.ok(response);
    }

    @Operation(summary = "Create a new connector",
               description = "Creates a new connector key. The key value is shown ONLY ONCE in the response. Store it securely.")
    @PostMapping("/")
    public SuccessResponse<ConnectorCreatedResponse> createConnector(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateConnectorRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        ConnectorCreateResult result = connectorService.createConnector(
                userPubId,
                request.name(),
                request.description(),
                request.connectorCode()
        );
        return SuccessResponse.ok(ConnectorCreatedResponse.from(
                result.app(),
                result.plaintextKey()
        ));
    }

    @Operation(summary = "Get a specific connector")
    @GetMapping("/{connectorId}")
    public SuccessResponse<ConnectorResponse> getConnector(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectorId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        App app = connectorService.getConnectorByPubIdForUser(connectorId, userPubId)
                .orElseThrow(() -> new NotFoundStatusException("App not found"));
        return SuccessResponse.ok(ConnectorResponse.from(app));
    }

    @Operation(summary = "Update a connector")
    @PutMapping("/{connectorId}")
    public SuccessResponse<ConnectorResponse> updateConnector(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectorId,
            @Valid @RequestBody UpdateConnectorRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        App updated = connectorService.updateConnector(
                connectorId,
                userPubId,
                request.name(),
                request.description(),
                request.enabled()
        );
        return SuccessResponse.ok(ConnectorResponse.from(updated));
    }

    @Operation(summary = "Delete a connector (soft delete)")
    @DeleteMapping("/{connectorId}")
    public SuccessResponse<Void> deleteConnector(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectorId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        connectorService.deleteConnector(connectorId, userPubId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Regenerate a connector key",
               description = "Invalidates the old key and creates a new one with the same settings")
    @PostMapping("/{connectorId}/regenerate")
    public SuccessResponse<ConnectorCreatedResponse> regenerateKey(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectorId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        ConnectorCreateResult result = connectorService.regenerateConnectorKey(connectorId, userPubId);
        return SuccessResponse.ok(ConnectorCreatedResponse.from(
                result.app(),
                result.plaintextKey()
        ));
    }

    @Operation(
            summary = "Get connector details",
            description = "Returns full connector information including device features, triggers and tools"
    )
    @GetMapping("/{connectorId}/detail")
    public SuccessResponse<UserConnectorDetailResponse> getConnectorDetail(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectorId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var app = connectorService.getConnectorByPubId(connectorId, userPubId);
        return SuccessResponse.ok(UserConnectorDetailResponse.from(app));
    }

    @Operation(
            summary = "Disconnect device from connector",
            description = "Removes device link from the specified connector"
    )
    @PostMapping("/{connectorId}/disconnect")
    public SuccessResponse<Void> disconnectConnector(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID connectorId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        connectorService.disconnectConnector(connectorId, userPubId);
        return SuccessResponse.empty();
    }
}
