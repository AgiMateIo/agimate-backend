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
import ru.agimate.deviceapi.controller.agent.dto.ToolUseRequest;
import ru.agimate.deviceapi.database.enums.PermissionDecision;
import ru.agimate.deviceapi.database.entities.ToolUseLog;
import ru.agimate.deviceapi.service.ConnectorApiService;
import ru.agimate.deviceapi.service.ToolUseAuthorizerService;
import ru.agimate.deviceapi.service.ToolUseLogService;

import java.util.UUID;

@RestController
@RequestMapping(AgentToolController.PATH)
@RequiredArgsConstructor
@Tag(name = "Connector Call", description = "Execute connector methods via API Key")
public class AgentToolController {

    public static final String PATH = AgentController.PATH + "/tool";

    private final ConnectorApiService connectorApiService;
    private final ToolUseLogService toolUseLogService;
    private final ToolUseAuthorizerService toolUseAuthorizerService;

    @Operation(
            summary = "Push tool_use to device",
            description = "Sends a tool use request to a specific device via Centrifugo",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Tool successfully pushed to device",
                    content = @Content(schema = @Schema(implementation = SuccessResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request body",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid or missing API key",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Tool use not authorized",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @PostMapping("/call/{connectorId}")
    public SuccessResponse<String> toolUse(
            @Parameter(description = "Connector identifier", required = true)
            @PathVariable String connectorId,
            @Valid @RequestBody ToolUseRequest toolUseRequest,
            @AuthenticationPrincipal ApiKeyPrincipal principal
    ) {
        UUID apiKeyPubId = UUID.fromString(principal.pubId());
        UUID userPubId = UUID.fromString(principal.userPubId());

        var existingLog = toolUseLogService.findByToolUseIdAndUserPubId(toolUseRequest.getId(), userPubId);
        if (existingLog.isPresent()) {
            return SuccessResponse.ok(existingLog.get().getToolUseId());
        }

        try {
            toolUseAuthorizerService.authorizeToolUseRequest(principal, connectorId, toolUseRequest.getName());
        } catch (ForbiddenStatusException e) {
            toolUseLogService.createLog(apiKeyPubId, userPubId, connectorId, toolUseRequest,
                    PermissionDecision.DENY, e.getMessage());
            throw e;
        }

        ToolUseLog log = toolUseLogService.createLog(apiKeyPubId, userPubId, connectorId, toolUseRequest,
                PermissionDecision.ALLOW, null);

        connectorApiService.pushToConnector(connectorId, toolUseRequest, principal.pubId());
        return SuccessResponse.ok(log.getToolUseId());
    }

    @Operation(
            summary = "Check tool_use permission",
            description = "Checks if a tool use request is authorized without pushing to device",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Permission check result",
                    content = @Content(schema = @Schema(implementation = SuccessResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request body",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid or missing API key",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @PostMapping("/check/{connectorId}")
    public SuccessResponse<PermissionDecision> checkToolUse(
            @Parameter(description = "Connector identifier", required = true)
            @PathVariable String connectorId,
            @Valid @RequestBody ToolUseRequest toolUseRequest,
            @AuthenticationPrincipal ApiKeyPrincipal principal
    ) {
        UUID apiKeyPubId = UUID.fromString(principal.pubId());
        UUID userPubId = UUID.fromString(principal.userPubId());

        var existingLog = toolUseLogService.findByToolUseIdAndUserPubId(toolUseRequest.getId(), userPubId);
        if (existingLog.isPresent()) {
            return SuccessResponse.ok(existingLog.get().getPermissionDecision());
        }

        try {
            toolUseAuthorizerService.authorizeToolUseRequest(principal, connectorId, toolUseRequest.getName());
        } catch (ForbiddenStatusException e) {
            toolUseLogService.createLog(apiKeyPubId, userPubId, connectorId, toolUseRequest,
                    PermissionDecision.DENY, e.getMessage());
            return SuccessResponse.ok(PermissionDecision.DENY);
        }

        toolUseLogService.createLog(apiKeyPubId, userPubId, connectorId, toolUseRequest,
                PermissionDecision.ALLOW, null);
        return SuccessResponse.ok(PermissionDecision.ALLOW);
    }
}
