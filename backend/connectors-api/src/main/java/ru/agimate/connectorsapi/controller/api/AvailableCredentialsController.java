package ru.agimate.connectorsapi.controller.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.connectorsapi.controller.api.dto.CredentialShortInfoResponse;
import ru.agimate.connectorsapi.service.CredentialService;

import java.util.List;

@RestController
@RequestMapping(AvailableCredentialsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Available credentials", description = "List of available credentials")
public class AvailableCredentialsController {

    public static final String PATH = "/api/credentials";

    private final CredentialService credentialService;


    @Operation(summary = "Get all available credentials")
    @GetMapping("/{connectorCode}")
    public SuccessResponse<List<CredentialShortInfoResponse>> getAvailableCredentials(
            @PathVariable String connectorCode
    ) {
        var apiKeyUserPubId = SecurityUtils.getApiKeyUserPubId();

        var listOfAvailableCredentials = credentialService.getAllCredentialsByUserPubIdAndConnectorCode(apiKeyUserPubId, connectorCode)
                .stream().map(projection -> new CredentialShortInfoResponse(
                        projection.getPubId(),
                        projection.getName(),
                        projection.getDescription(),
                        projection.getConnectorCode()
                ))
                .toList();

        return SuccessResponse.ok(listOfAvailableCredentials);
    }
}
