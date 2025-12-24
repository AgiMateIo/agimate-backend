package ru.agimate.connectorsapi.controller;

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
import ru.agimate.connectorsapi.service.CredentialService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/credentials")
@RequiredArgsConstructor
@Tag(name = "Credentials", description = "Manage connector credentials")
public class CredentialController {

    private final CredentialService credentialService;

    @Operation(summary = "Get credentials summary for all connectors")
    @GetMapping
    public SuccessResponse<List<ConnectorSummaryResponse>> getCredentialsSummary() {
        return SuccessResponse.ok(credentialService.getCredentialsSummary());
    }

    @Operation(summary = "Get all credentials for a connector")
    @GetMapping("/{connectorCode}")
    public SuccessResponse<List<CredentialResponse>> getCredentials(
            @PathVariable String connectorCode
    ) {
        return SuccessResponse.ok(credentialService.getCredentials(connectorCode));
    }

    @Operation(summary = "Create new credential")
    @PostMapping("/{connectorCode}")
    public SuccessResponse<CredentialResponse> createCredential(
            @PathVariable String connectorCode,
            @Valid @RequestBody CreateCredentialRequest request
    ) {
        return SuccessResponse.ok(credentialService.createCredential(connectorCode, request));
    }

    @Operation(summary = "Get credential details")
    @GetMapping("/{connectorCode}/{credentialId}")
    public SuccessResponse<CredentialResponse> getCredential(
            @PathVariable String connectorCode,
            @PathVariable UUID credentialId
    ) {
        return SuccessResponse.ok(credentialService.getCredential(connectorCode, credentialId));
    }

    @Operation(summary = "Update credential")
    @PutMapping("/{connectorCode}/{credentialId}")
    public SuccessResponse<CredentialResponse> updateCredential(
            @PathVariable String connectorCode,
            @PathVariable UUID credentialId,
            @Valid @RequestBody UpdateCredentialRequest request
    ) {
        return SuccessResponse.ok(credentialService.updateCredential(connectorCode, credentialId, request));
    }

    @Operation(summary = "Delete credential")
    @DeleteMapping("/{connectorCode}/{credentialId}")
    public SuccessResponse<Void> deleteCredential(
            @PathVariable String connectorCode,
            @PathVariable UUID credentialId
    ) {
        credentialService.deleteCredential(connectorCode, credentialId);
        return SuccessResponse.empty();
    }
}
