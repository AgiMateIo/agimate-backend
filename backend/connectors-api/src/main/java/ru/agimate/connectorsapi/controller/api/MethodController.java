package ru.agimate.connectorsapi.controller.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.connectorsapi.connector.ConnectorMethod;
import ru.agimate.connectorsapi.connector.ConnectorRegistry;
import ru.agimate.connectorsapi.controller.dto.response.MethodResponse;

import java.util.List;

@RestController
@RequestMapping(MethodController.PATH)
@RequiredArgsConstructor
@Tag(name = "Methods", description = "Connector methods")
public class MethodController {

    public static final String PATH = "/api/methods";

    private final ConnectorRegistry connectorRegistry;

    @Operation(summary = "Get all methods for a connector")
    @GetMapping("/{connectorCode}")
    public SuccessResponse<List<MethodResponse>> getMethods(
            @PathVariable String connectorCode
    ) {
        List<ConnectorMethod> methods = connectorRegistry.getMethods(connectorCode);
        List<MethodResponse> response = methods.stream()
                .map(MethodResponse::from)
                .toList();
        return SuccessResponse.ok(response);
    }
}
