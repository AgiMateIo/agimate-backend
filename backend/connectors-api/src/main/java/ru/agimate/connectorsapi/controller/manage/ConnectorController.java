package ru.agimate.connectorsapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import java.util.Map;
import ru.agimate.connectorsapi.controller.manage.dto.response.ConnectorInfoResponse;
import ru.agimate.connectorsapi.service.ConnectorService;

import java.util.List;

@RestController
@RequestMapping(ConnectorController.PATH)
@RequiredArgsConstructor
@Tag(name = "Connectors", description = "Available connectors")
public class ConnectorController {

    public static final String PATH = "/connectors";

    private final ConnectorService connectorService;

    // Hardcoded metadata for implemented connectors (temporary solution)
    private static final Map<String, List<String>> CONNECTOR_REQUIRED_FIELDS = Map.of(
            "ozon", List.of("clientId", "apiKey"),
            "wildberries", List.of("apiKey")
    );

    @Operation(summary = "Get all available connectors")
    @GetMapping
    public SuccessResponse<List<ConnectorInfoResponse>> getAllConnectors() {
        List<ConnectorInfoResponse> connectors = connectorService.getAllConnectors()
                .stream()
                .map(connector -> {
                    String code = connector.getCode();
                    boolean hasMethods = CONNECTOR_REQUIRED_FIELDS.containsKey(code);
                    List<String> requiredFields = CONNECTOR_REQUIRED_FIELDS.getOrDefault(code, List.of());
                    return ConnectorInfoResponse.from(connector, requiredFields, hasMethods);
                })
                .toList();
        return SuccessResponse.ok(connectors);
    }
}
