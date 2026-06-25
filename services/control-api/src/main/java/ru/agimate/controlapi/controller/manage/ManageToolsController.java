package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.service.tool.ToolDefinitionService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(ManageToolsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Manage Tools", description = "Tool definitions for manage UI (JWT)")
public class ManageToolsController {

    public static final String PATH = "/manage/tools";

    private final ToolDefinitionService toolDefinitionService;

    @Operation(
            summary = "Get available tools for a connector",
            description = "Returns tool definitions for the given connector. " +
                    "For APP connectors the `identity` query parameter (App id) is required."
    )
    @GetMapping("/{connectorCode}/")
    public SuccessResponse<Map<String, ConnectorToolSpec>> getTools(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable String connectorCode,
            @RequestParam(required = false) UUID identity
    ) {
        return SuccessResponse.ok(toolDefinitionService.getTools(connectorCode, identity));
    }

    @Operation(
            summary = "Get parameters of a specific tool",
            description = "Returns parameter JSON Schema for one tool by connectorCode + toolName " +
                    "(plus identity for APP connectors)."
    )
    @GetMapping("/{connectorCode}/{toolName}")
    public SuccessResponse<ConnectorToolSpec> getTool(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable String connectorCode,
            @PathVariable String toolName,
            @RequestParam(required = false) UUID identity
    ) {
        return SuccessResponse.ok(toolDefinitionService.getTool(connectorCode, toolName, identity));
    }
}
