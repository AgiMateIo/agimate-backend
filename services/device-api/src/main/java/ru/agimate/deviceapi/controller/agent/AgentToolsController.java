package ru.agimate.deviceapi.controller.agent;

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
import ru.agimate.deviceapi.connectors.integrations.IntegrationHandler;
import ru.agimate.deviceapi.connectors.integrations.IntegrationsRegistry;
import ru.agimate.deviceapi.controller.agent.dto.ToolSpecificationMapper;
import ru.agimate.deviceapi.controller.agent.dto.ToolSpecificationResponse;
import ru.agimate.deviceapi.security.AgentPrincipal;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping(AgentToolsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Connector Call", description = "Execute connector methods via API Key")
public class AgentToolsController {

    public static final String PATH = AgentController.PATH + "/tools";

    private final IntegrationsRegistry integrationsRegistry;

    @Operation(
            summary = "Get available tools",
            description = "Returns all tool definitions available to the authenticated agent",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @GetMapping("/integrations/{connectorCode}")
    public SuccessResponse<Map<String, ToolSpecificationResponse>> getAvailableTools(
            @AuthenticationPrincipal AgentPrincipal principal,
            @PathVariable("connectorCode") String connectorCode) {

        IntegrationHandler handler = integrationsRegistry.getHandler(connectorCode);

        Map<String, ToolSpecificationResponse> result = new LinkedHashMap<>();
        handler.getPredefinedTools().forEach((name, spec) ->
                result.put(name, ToolSpecificationMapper.toResponse(spec)));
        return SuccessResponse.ok(result);
    }


}
