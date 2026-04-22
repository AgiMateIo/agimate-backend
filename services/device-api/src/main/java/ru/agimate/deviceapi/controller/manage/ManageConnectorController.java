package ru.agimate.deviceapi.controller.manage;

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
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.connectors.integrations.IntegrationsRegistry;
import ru.agimate.deviceapi.controller.manage.dto.ConnectorResponse;
import ru.agimate.deviceapi.controller.manage.dto.IntegrationMeta;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.database.enums.ConnectorType;
import ru.agimate.deviceapi.database.repositories.ConnectorRepository;

@RestController
@RequestMapping(ManageConnectorController.PATH)
@RequiredArgsConstructor
@Tag(name = "Connectors", description = "Connector catalog")
public class ManageConnectorController {

    public static final String PATH = "/manage/connectors";

    private final ConnectorRepository connectorRepository;
    private final IntegrationsRegistry integrationsRegistry;

    @Operation(summary = "List available connectors with optional type filter and full-text search")
    @GetMapping("/")
    public SuccessResponse<Page<ConnectorResponse>> getAll(
            @RequestParam(required = false) ConnectorType type,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();
        PageRequest pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        Page<ConnectorResponse> response = connectorRepository.search(type, normalizedSearch, pageable)
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

    private ConnectorResponse toResponse(Connector connector) {
        if (connector.getType() == ConnectorType.INTEGRATION) {
            return integrationsRegistry.findHandler(connector.getCode())
                    .map(handler -> ConnectorResponse.from(connector, IntegrationMeta.from(handler)))
                    .orElseGet(() -> ConnectorResponse.from(connector));
        }
        return ConnectorResponse.from(connector);
    }
}
