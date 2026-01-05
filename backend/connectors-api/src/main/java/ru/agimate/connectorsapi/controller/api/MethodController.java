package ru.agimate.connectorsapi.controller.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.connectorsapi.controller.dto.MethodInfo;
import ru.agimate.connectorsapi.service.OpenApiMethodExtractor;

import java.util.List;

@RestController
@RequestMapping(MethodController.PATH)
@RequiredArgsConstructor
@Tag(name = "Methods", description = "Connector methods - extracted from OpenAPI specification")
public class MethodController {

    public static final String PATH = "/api/methods";

    private final OpenApiMethodExtractor openApiMethodExtractor;

    @Operation(
            summary = "Get all methods for a connector",
            description = "Returns list of available methods for the specified connector. " +
                    "Method information is extracted from OpenAPI specification dynamically, " +
                    "so it automatically includes all methods defined in connector controllers."
    )
    @GetMapping("/{connectorCode}")
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
