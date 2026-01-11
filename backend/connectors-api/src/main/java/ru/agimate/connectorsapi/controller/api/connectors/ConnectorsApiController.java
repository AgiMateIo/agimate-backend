package ru.agimate.connectorsapi.controller.api.connectors;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.connectorsapi.controller.api.connectors.dto.ConnectorShorInfoResponse;
import ru.agimate.connectorsapi.controller.api.connectors.dto.CredentialShortInfoResponse;
import ru.agimate.connectorsapi.controller.api.connectors.dto.MethodInfo;
import ru.agimate.connectorsapi.service.ConnectorService;
import ru.agimate.connectorsapi.service.CredentialService;
import ru.agimate.connectorsapi.service.OpenApiMethodExtractor;

import java.util.List;

@RestController
@RequestMapping(ConnectorsApiController.PATH)
@RequiredArgsConstructor
@Tag(name = "Available connectors", description = "List of available connectors")
public class ConnectorsApiController {

    public static final String PATH = "/api/connectors";

    private final ConnectorService connectorService;
    private final CredentialService credentialService;
    private final OpenApiMethodExtractor openApiMethodExtractor;


    @Operation(
            summary = "Get available connectors",
            description = "Returns list of connectors available to the user - those with configured credentials plus mobile connector"
    )
    @GetMapping("/")
    public SuccessResponse<List<ConnectorShorInfoResponse>> getAvailableConnectors() {
        var apiKeyUserPubId = SecurityUtils.getApiKeyUserPubId();

        var listOfAvailableConnectors = connectorService.getAvailableConnectorsForUser(apiKeyUserPubId)
                .stream()
                .map(connector -> new ConnectorShorInfoResponse(
                    connector.getName(),
                    connector.getDescription(),
                    connector.getCode()
                ))
                .toList();

        return SuccessResponse.ok(listOfAvailableConnectors);
    }

    @Operation(
            summary = "Get available credentials",
            description = "Returns list of credentials configured for the specified connector"
    )
    @GetMapping("/credentials/{connectorCode}/")
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


    @Operation(
            summary = "Get all methods for a connector",
            description = "Returns list of available methods for the specified connector. " +
                    "Method information is extracted from OpenAPI specification dynamically, " +
                    "so it automatically includes all methods defined in connector controllers."
    )
    @GetMapping("/methods/{connectorCode}/")
    public SuccessResponse<List<MethodInfo>> getMethods(
            @PathVariable String connectorCode
    ) {
        List<MethodInfo> methods = openApiMethodExtractor.extractMethodsForConnector(connectorCode);

        if (methods.isEmpty()) {
            throw new NotFoundStatusException("Connector not found: " + connectorCode);
        }

        return SuccessResponse.ok(methods);
    }
}
