package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.controller.manage.dto.ConnectorResponse;
import ru.agimate.controlapi.controller.manage.dto.IntegrationMeta;
import ru.agimate.controlapi.controller.manage.dto.TriggerSpecificationResponse;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.service.tool.ToolDefinitionService;

import java.util.List;

@RestController
@RequestMapping(ManageConnectorController.PATH)
@RequiredArgsConstructor
@Tag(name = "Connectors", description = "Connector catalog")
public class ManageConnectorController {

    public static final String PATH = "/manage/connectors";

    private final ConnectorRepository connectorRepository;
    private final ConnectorRegistry connectorRegistry;
    private final ToolDefinitionService toolDefinitionService;

    @Operation(summary = "List available connectors with optional full-text search")
    @GetMapping("/")
    public SuccessResponse<Page<ConnectorResponse>> getAll(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();
        PageRequest pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        Page<ConnectorResponse> response = connectorRepository.search(normalizedSearch, pageable)
                .map(this::toResponse);
        return SuccessResponse.ok(response);
    }

    @Operation(summary = "Get connector by code")
    @GetMapping("/{code}")
    public SuccessResponse<ConnectorResponse> getByCode(@PathVariable String code) {
        Connector connector = connectorRepository.findById(code)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + code));
        return SuccessResponse.ok(toResponse(connector));
    }

    @Operation(summary = "List the connector type's predefined tools (STATIC connectors; empty for DYNAMIC)")
    @GetMapping("/{code}/tools/")
    public SuccessResponse<List<ConnectorToolSpec>> getTools(@PathVariable String code) {
        return SuccessResponse.ok(toolDefinitionService.getCatalogTools(code).values().stream().toList());
    }

    @Operation(summary = "Get the parameter schema of a single catalog tool")
    @GetMapping("/{code}/tools/{toolName}")
    public SuccessResponse<ConnectorToolSpec> getTool(
            @PathVariable String code,
            @PathVariable String toolName
    ) {
        return SuccessResponse.ok(toolDefinitionService.getCatalogTool(code, toolName));
    }

    @Operation(summary = "List predefined triggers exposed by an integration connector type")
    @GetMapping("/{code}/triggers/")
    public SuccessResponse<List<TriggerSpecificationResponse>> getTriggers(@PathVariable String code) {
        IntegrationConnectorHandler handler = integrationHandler(code);
        return SuccessResponse.ok(handler.getTriggers().entrySet().stream()
                .map(e -> TriggerSpecificationResponse.from(e.getKey(), e.getValue()))
                .toList());
    }

    private IntegrationConnectorHandler integrationHandler(String code) {
        return connectorRegistry.findIntegrationHandler(code)
                .orElseThrow(() -> new BadRequestStatusException("Connector is not an integration: " + code));
    }

    private ConnectorResponse toResponse(Connector connector) {
        if (connector.isIntegration()) {
            return connectorRegistry.findIntegrationHandler(connector.getCode())
                    .map(handler -> ConnectorResponse.from(connector, IntegrationMeta.from(handler)))
                    .orElseGet(() -> ConnectorResponse.from(connector));
        }
        return ConnectorResponse.from(connector);
    }
}
