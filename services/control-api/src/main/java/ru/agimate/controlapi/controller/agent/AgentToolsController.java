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
import dev.langchain4j.agent.tool.ToolSpecification;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.integrations.IntegrationsRegistry;
import ru.agimate.controlapi.connectors.internal.InternalConnectorRegistry;
import ru.agimate.controlapi.controller.agent.dto.ToolSpecificationMapper;
import ru.agimate.controlapi.controller.agent.dto.ToolSpecificationResponse;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.security.AgentPrincipal;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping(AgentToolsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Tool Definitions", description = "Get tool schemas via API Key")
public class AgentToolsController {

    public static final String PATH = AgentController.PATH + "/tools";

    private final IntegrationsRegistry integrationsRegistry;
    private final InternalConnectorRegistry internalConnectorRegistry;
    private final ConnectorRepository connectorRepository;

    @Operation(
            summary = "Get available tools for a connector",
            description = "Returns tool definitions for the given connector code. " +
                    "Tool source (integration vs internal service) is resolved from the connector's type.",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @GetMapping("/{connectorCode}")
    public SuccessResponse<Map<String, ToolSpecificationResponse>> getTools(
            @AuthenticationPrincipal AgentPrincipal principal,
            @PathVariable("connectorCode") String connectorCode) {

        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));

        Map<String, ToolSpecification> tools = switch (connector.getType()) {
            case INTEGRATION -> integrationsRegistry.getHandler(connectorCode).getPredefinedTools();
            case INTERNAL_SERVICE -> internalConnectorRegistry.getHandler(connectorCode).getToolDefinitions();
            case APP, LOOPBACK -> throw new BadRequestStatusException(
                    "Connector type " + connector.getType() + " does not expose static tool definitions");
        };

        Map<String, ToolSpecificationResponse> result = new LinkedHashMap<>();
        tools.forEach((name, spec) -> result.put(name, ToolSpecificationMapper.toResponse(spec)));
        return SuccessResponse.ok(result);
    }
}
