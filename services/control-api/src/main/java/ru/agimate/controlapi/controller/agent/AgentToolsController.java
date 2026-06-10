package ru.agimate.controlapi.controller.agent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.security.AgentPrincipal;

import java.util.Map;

@RestController
@RequestMapping(AgentToolsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Tool Definitions", description = "Get tool schemas via API Key")
public class AgentToolsController {

    public static final String PATH = AgentController.PATH + "/tools";

    private final ConnectorRegistry connectorRegistry;
    private final ConnectorRepository connectorRepository;

    @Operation(
            summary = "Get available tools for a connector",
            description = "Returns tool definitions for the given connector code. " +
                    "Tool source (integration vs internal service) is resolved from the connector's type.",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @GetMapping("/{connectorCode}")
    public SuccessResponse<Map<String, ConnectorToolSpec>> getTools(
            @AuthenticationPrincipal AgentPrincipal principal,
            @PathVariable("connectorCode") String connectorCode) {

        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));

        Map<String, ConnectorToolSpec> tools = switch (connector.getType()) {
            case INTEGRATION, INTERNAL_SERVICE -> connectorRegistry.findHandler(connectorCode)
                    .orElseThrow(() -> new BadRequestStatusException("Unsupported connector: " + connectorCode))
                    .getTools();
            case APP, LOOPBACK -> throw new BadRequestStatusException(
                    "Connector type " + connector.getType() + " does not expose static tool definitions");
        };

        return SuccessResponse.ok(tools);
    }
}
