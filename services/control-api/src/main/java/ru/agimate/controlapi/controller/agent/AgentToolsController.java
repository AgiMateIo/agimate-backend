package ru.agimate.controlapi.controller.agent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.controller.agent.dto.ToolDefinition;
import ru.agimate.controlapi.security.AgentPrincipal;
import ru.agimate.controlapi.service.AgentService;
import ru.agimate.controlapi.service.tool.ToolDefinitionService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(AgentToolsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Tool Definitions", description = "Get tool schemas via API Key")
public class AgentToolsController {

    public static final String PATH = AgentController.PATH + "/tools";

    private final ToolDefinitionService toolDefinitionService;
    private final AgentService agentService;

    @Operation(
            summary = "Get available tools",
            description = "Returns all tool definitions available to the authenticated agent",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @GetMapping("/")
    public SuccessResponse<List<ToolDefinition>> getAvailableTools(
            @AuthenticationPrincipal AgentPrincipal principal) {
        return SuccessResponse.ok(agentService.getAvailableTools(principal.agentId()));
    }

    @Operation(
            summary = "Get available tools for a connector",
            description = "Returns tool definitions for the given connector code. Tool source is resolved " +
                    "from the connector's toolBinding; dynamic connectors need an instance connectionId.",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @GetMapping("/{connectorCode}")
    public SuccessResponse<Map<String, ConnectorToolSpec>> getTools(
            @AuthenticationPrincipal AgentPrincipal principal,
            @PathVariable("connectorCode") String connectorCode,
            @RequestParam(required = false) UUID connectionId) {
        return SuccessResponse.ok(toolDefinitionService.getTools(principal.userId(), connectorCode, connectionId));
    }
}
