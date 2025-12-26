package ru.agimate.connectorsapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.connectorsapi.controller.dto.request.CreateCredentialRequest;
import ru.agimate.connectorsapi.controller.dto.request.UpdateCredentialRequest;
import ru.agimate.connectorsapi.controller.dto.response.ConnectorSummaryResponse;
import ru.agimate.connectorsapi.controller.dto.response.CredentialResponse;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.connectorsapi.service.CredentialService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(CredentialController.PATH)
@RequiredArgsConstructor
@Tag(name = "Credentials", description = "Manage connector credentials")
public class CredentialController {

    public static final String PATH = "/credentials";

    private final CredentialService credentialService;

    @Operation(summary = "Get credentials summary for all connectors")
    @GetMapping
    public SuccessResponse<List<ConnectorSummaryResponse>> getCredentialsSummary() {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(credentialService.getCredentialsSummary(userPubId));
    }

    @Operation(summary = "Get all credentials for a connector")
    @GetMapping("/{connectorCode}")
    public SuccessResponse<List<CredentialResponse>> getCredentials(
            @PathVariable String connectorCode
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(credentialService.getCredentials(connectorCode, userPubId));
    }

    @Operation(summary = "Create new credential")
    @PostMapping("/{connectorCode}")
    public SuccessResponse<CredentialResponse> createCredential(
            @PathVariable String connectorCode,
            @Valid @RequestBody CreateCredentialRequest request
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(credentialService.createCredential(connectorCode, request, userPubId));
    }

    @Operation(summary = "Get credential details")
    @GetMapping("/{connectorCode}/{credentialId}")
    public SuccessResponse<CredentialResponse> getCredential(
            @PathVariable String connectorCode,
            @PathVariable UUID credentialId
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(credentialService.getCredential(connectorCode, credentialId, userPubId));
    }

    @Operation(summary = "Update credential")
    @PutMapping("/{connectorCode}/{credentialId}")
    public SuccessResponse<CredentialResponse> updateCredential(
            @PathVariable String connectorCode,
            @PathVariable UUID credentialId,
            @Valid @RequestBody UpdateCredentialRequest request
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(credentialService.updateCredential(connectorCode, credentialId, request, userPubId));
    }

    @Operation(summary = "Delete credential")
    @DeleteMapping("/{connectorCode}/{credentialId}")
    public SuccessResponse<Void> deleteCredential(
            @PathVariable String connectorCode,
            @PathVariable UUID credentialId
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        credentialService.deleteCredential(connectorCode, credentialId, userPubId);
        return SuccessResponse.empty();
    }
}
