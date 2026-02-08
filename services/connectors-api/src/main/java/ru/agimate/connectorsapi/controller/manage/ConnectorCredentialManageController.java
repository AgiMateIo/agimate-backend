package ru.agimate.connectorsapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.connectorsapi.controller.manage.dto.request.CreateConnectorCredentialRequest;
import ru.agimate.connectorsapi.controller.manage.dto.request.UpdateConnectorCredentialRequest;
import ru.agimate.connectorsapi.controller.manage.dto.response.ConnectorSummaryResponse;
import ru.agimate.connectorsapi.controller.manage.dto.response.ConnectorCredentialResponse;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.connectorsapi.service.ConnectorCredentialService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ConnectorCredentialManageController.PATH)
@RequiredArgsConstructor
@Tag(name = "Connector Credentials", description = "Manage connector credentials")
public class ConnectorCredentialManageController {

    public static final String PATH = "/manage/credentials";

    private final ConnectorCredentialService connectorCredentialService;

    @Operation(summary = "Get credentials summary for all connectors")
    @GetMapping("/")
    public SuccessResponse<List<ConnectorSummaryResponse>> getCredentialsSummary() {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(connectorCredentialService.getCredentialsSummary(userPubId));
    }

    @Operation(summary = "Get all credentials for a connector")
    @GetMapping("/{connectorCode}/")
    public SuccessResponse<List<ConnectorCredentialResponse>> getCredentials(
            @PathVariable String connectorCode
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(connectorCredentialService.getCredentials(connectorCode, userPubId));
    }

    @Operation(summary = "Create new credential")
    @PostMapping("/{connectorCode}")
    public SuccessResponse<ConnectorCredentialResponse> createCredential(
            @PathVariable String connectorCode,
            @Valid @RequestBody CreateConnectorCredentialRequest request
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(connectorCredentialService.createCredential(connectorCode, request, userPubId));
    }

    @Operation(summary = "Get credential details")
    @GetMapping("/{connectorCode}/{credentialId}")
    public SuccessResponse<ConnectorCredentialResponse> getCredential(
            @PathVariable String connectorCode,
            @PathVariable UUID credentialId
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(connectorCredentialService.getCredential(connectorCode, credentialId, userPubId));
    }

    @Operation(summary = "Update credential")
    @PutMapping("/{connectorCode}/{credentialId}")
    public SuccessResponse<ConnectorCredentialResponse> updateCredential(
            @PathVariable String connectorCode,
            @PathVariable UUID credentialId,
            @Valid @RequestBody UpdateConnectorCredentialRequest request
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(connectorCredentialService.updateCredential(connectorCode, credentialId, request, userPubId));
    }

    @Operation(summary = "Delete credential")
    @DeleteMapping("/{connectorCode}/{credentialId}")
    public SuccessResponse<Void> deleteCredential(
            @PathVariable String connectorCode,
            @PathVariable UUID credentialId
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        connectorCredentialService.deleteCredential(connectorCode, credentialId, userPubId);
        return SuccessResponse.empty();
    }
}
