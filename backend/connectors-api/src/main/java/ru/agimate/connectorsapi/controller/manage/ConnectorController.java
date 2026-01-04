package ru.agimate.connectorsapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.connectorsapi.connector.ConnectorRegistry;
import ru.agimate.connectorsapi.controller.dto.response.ConnectorInfoResponse;
import ru.agimate.connectorsapi.service.ConnectorService;

import java.util.List;

@RestController
@RequestMapping(ConnectorController.PATH)
@RequiredArgsConstructor
@Tag(name = "Connectors", description = "Available connectors")
public class ConnectorController {

    public static final String PATH = "/connectors";

    private final ConnectorService connectorService;
    private final ConnectorRegistry connectorRegistry;

    @Operation(summary = "Get all available connectors")
    @GetMapping
    public SuccessResponse<List<ConnectorInfoResponse>> getAllConnectors() {
        List<ConnectorInfoResponse> connectors = connectorService.getAllConnectors()
                .stream()
                .map(connector -> {
                    boolean hasMethods = connectorRegistry.hasDefinition(connector.getCode());
                    List<String> requiredFields = hasMethods
                            ? connectorRegistry.getRequiredCredentialFields(connector.getCode())
                            : List.of();
                    return ConnectorInfoResponse.from(connector, requiredFields, hasMethods);
                })
                .toList();
        return SuccessResponse.ok(connectors);
    }
}
