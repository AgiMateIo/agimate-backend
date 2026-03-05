package ru.agimate.deviceapi.controller.agent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.ErrorResponse;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.security.apikey.ApiKeyPrincipal;
import ru.agimate.deviceapi.abac.AccessDecision;
import ru.agimate.deviceapi.abac.ToolPolicyDbEvaluatorService;
import ru.agimate.deviceapi.controller.agent.dto.AgentToolResultRequest;
import ru.agimate.deviceapi.controller.agent.dto.ToolDefinition;
import ru.agimate.deviceapi.controller.agent.dto.ToolUseRequest;
import ru.agimate.deviceapi.abac.AccessEffect;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.ToolUseLog;
import ru.agimate.deviceapi.service.AgentService;
import ru.agimate.deviceapi.service.ConnectorApiService;
import ru.agimate.deviceapi.service.ToolUseLogService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(AgentToolController.PATH)
@RequiredArgsConstructor
@Tag(name = "Connector Call", description = "Execute connector methods via API Key")
public class AgentToolController {

    public static final String PATH = AgentController.PATH + "/tool";

    private final ConnectorApiService connectorApiService;
    private final ToolUseLogService toolUseLogService;
    private final ToolPolicyDbEvaluatorService toolPolicyDbEvaluatorService;
    private final AgentService agentService;

    @Operation(
            summary = "Get available tools",
            description = "Returns all tool definitions available to the authenticated agent",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @GetMapping("/")
    public SuccessResponse<List<ToolDefinition>> getAvailableTools(
            @AuthenticationPrincipal ApiKeyPrincipal principal
    ) {
        UUID apiKeyPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agentService.getAvailableTools(apiKeyPubId));
    }

    @Operation(
            summary = "Push tool_use to device",
            description = "Sends a tool use request to a specific device via Centrifugo",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @PostMapping("/call/{connectorCode}")
    public SuccessResponse<String> toolUse(
            @Parameter(description = "Connector code", required = true)
            @PathVariable String connectorCode,
            @Valid @RequestBody ToolUseRequest toolUseRequest,
            @AuthenticationPrincipal ApiKeyPrincipal apiKeyPrincipal
    ) {
        Agent agent = agentService.findByApiKeyPubId(UUID.fromString(apiKeyPrincipal.pubId()));

        var existingLog = toolUseLogService.findByToolUseIdAndUserPubId(toolUseRequest.getId(), agent.getUserPubId());
        if (existingLog.isPresent()) {
            return SuccessResponse.ok(existingLog.get().getToolUseId());
        }

        AccessDecision decision = toolPolicyDbEvaluatorService.evaluate(
                agent.getApiKeyPubId(), connectorCode, toolUseRequest.getIdentity(), toolUseRequest.getName());

        ToolUseLog log = toolUseLogService.createLog(agent, connectorCode, toolUseRequest.getIdentity(), toolUseRequest,
                toolUseRequest.getAgentSessionId(), decision.accessEffect(), decision.reason());

        if (!decision.allowed()) {
            throw new ForbiddenStatusException("Tool '" + toolUseRequest.getName() + "' is not authorized for this agent: " + decision.reason());
        }

        connectorApiService.pushToConnector(agent.getUserPubId(), agent.getApiKeyPubId().toString(), connectorCode, toolUseRequest.getIdentity(), toolUseRequest);
        return SuccessResponse.ok(log.getToolUseId());
    }

    @Operation(
            summary = "Check tool_use permission",
            description = "Checks if a tool use request is authorized without pushing to device",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @PostMapping("/check/{connectorCode}")
    public SuccessResponse<AccessEffect> checkToolUse(
            @Parameter(description = "Connector code", required = true)
            @PathVariable String connectorCode,
            @Valid @RequestBody ToolUseRequest toolUseRequest,
            @AuthenticationPrincipal ApiKeyPrincipal principal
    ) {
        Agent agent = agentService.findByApiKeyPubId(UUID.fromString(principal.pubId()));

        var existingLog = toolUseLogService.findByToolUseIdAndUserPubId(toolUseRequest.getId(), agent.getUserPubId());
        if (existingLog.isPresent()) {
            return SuccessResponse.ok(existingLog.get().getAccessEffect());
        }

        AccessDecision decision = toolPolicyDbEvaluatorService.evaluate(
                agent.getApiKeyPubId(), connectorCode, toolUseRequest.getIdentity(), toolUseRequest.getName());

        toolUseLogService.createLog(agent, connectorCode, toolUseRequest.getIdentity(), toolUseRequest,
                toolUseRequest.getAgentSessionId(), decision.accessEffect(), decision.reason());

        return SuccessResponse.ok(decision.accessEffect());
    }

    @Operation(
            summary = "Save tool_use result",
            description = "Saves the result of a tool use execution. Only allowed for tool uses with ALLOW permission decision.",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Result saved successfully",
                    content = @Content(schema = @Schema(implementation = SuccessResponse.class))
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Not allowed to save result (denied tool use or wrong agent)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Tool use log not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @PostMapping("/result")
    public SuccessResponse<String> saveToolResult(
            @Valid @RequestBody AgentToolResultRequest request,
            @AuthenticationPrincipal ApiKeyPrincipal principal
    ) {
        Agent agent = agentService.findByApiKeyPubId(UUID.fromString(principal.pubId()));

        var toolUseLog = toolUseLogService.recordOutputByAgent(
                agent.getApiKeyPubId(), request.getToolUseId(), request.getOutput(), request.getError());

        return SuccessResponse.ok(toolUseLog.getToolUseId());
    }
}
