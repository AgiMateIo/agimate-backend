package ru.agimate.connectorsapi.controller.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.connectorsapi.controller.api.dto.ConnectorShorInfoResponse;
import ru.agimate.connectorsapi.service.ConnectorService;

import java.util.List;

@RestController
@RequestMapping(AvailableConnectorsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Available connectors", description = "List of available connectors")
public class AvailableConnectorsController {

    public static final String PATH = "/api/connectors";

    private final ConnectorService connectorService;


    @Operation(summary = "Get all available connectors for user (with credentials + mobile)")
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
}
