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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.ErrorResponse;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.deviceapi.controller.agent.dto.AgentToolResultRequest;
import ru.agimate.deviceapi.controller.agent.dto.ToolDefinition;
import ru.agimate.deviceapi.controller.agent.dto.ToolUseRequest;
import ru.agimate.deviceapi.abac.AccessEffect;
import ru.agimate.deviceapi.security.AgentPrincipal;
import ru.agimate.deviceapi.service.AgentService;
import ru.agimate.deviceapi.service.AgentToolUseService;

import java.util.List;

@RestController
@RequestMapping(AgentToolCallController.PATH)
@RequiredArgsConstructor
@Tag(name = "Tool Call Controller", description = "Execute connector methods via API Key")
public class AgentToolCallController {

    public static final String PATH = AgentController.PATH + "/tool";

    private final AgentToolUseService agentToolUseService;
    private final AgentService agentService;

    @Operation(
            summary = "Get available tools",
            description = "Returns all tool definitions available to the authenticated agent",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @GetMapping("/")
    public SuccessResponse<List<ToolDefinition>> getAvailableTools(
            @AuthenticationPrincipal AgentPrincipal principal
    ) {
        return SuccessResponse.ok(agentService.getAvailableTools(principal.agentId()));
    }


    @Operation(
            summary = "Check tool_use permission",
            description = "Checks if a tool use request is authorized without pushing to device",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @PostMapping("/check")
    public SuccessResponse<AccessEffect> checkToolUse(
            @Parameter(description = "Connector code", required = true)
            @Valid @RequestBody ToolUseRequest toolUseRequest,
            @AuthenticationPrincipal AgentPrincipal principal
    ) {
        return SuccessResponse.ok(
                agentToolUseService.checkToolUse(principal.agentId(), toolUseRequest));
    }

    @Operation(
            summary = "Push tool_use to device",
            description = "Sends a tool use request to a specific device via Centrifugo",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @PostMapping("/call")
    public SuccessResponse<String> toolUse(
            @Parameter(description = "Connector code", required = true)
            @Valid @RequestBody ToolUseRequest toolUseRequest,
            @AuthenticationPrincipal AgentPrincipal principal
    ) {
        return SuccessResponse.ok(
                agentToolUseService.processToolUse(principal.agentId(), toolUseRequest));
    }

    @Operation(
            summary = "Get tool_use result",
            description = "Returns the tool use output if execution completed successfully, error if failed, or 204 if result is not yet available.",
            security = @SecurityRequirement(name = "ApiKey")
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Execution completed (output or error)",
                    content = @Content(schema = @Schema(oneOf = {SuccessResponse.class, ErrorResponse.class}))
            ),
            @ApiResponse(
                    responseCode = "204",
                    description = "Result not yet available",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Tool use log not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @GetMapping("/result/{toolUseId}")
    public ResponseEntity<?> getToolResult(
            @PathVariable String toolUseId,
            @AuthenticationPrincipal AgentPrincipal principal
    ) {
        var log = agentToolUseService.getToolUseLog(principal.agentId(), toolUseId);

        if (log.getOutputAt() == null) {
            return ResponseEntity.noContent().build();
        }

        if (log.getError() != null) {
            return ResponseEntity.ok(new ErrorResponse(log.getError()));
        }

        return ResponseEntity.ok(SuccessResponse.ok(log.getOutput()));
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
            @AuthenticationPrincipal AgentPrincipal principal
    ) {
        var log = agentToolUseService.saveToolResult(principal.agentId(), request);
        return SuccessResponse.ok(log.getToolUseId());
    }
}
