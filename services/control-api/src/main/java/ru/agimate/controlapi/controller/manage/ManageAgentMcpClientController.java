package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.service.mcp.oauth.McpGrantService;
import ru.agimate.controlapi.service.mcp.oauth.McpGrantView;

import java.util.List;
import java.util.UUID;

/** External MCP clients connected to an agent over OAuth; the agent's key is not among them. */
@RestController
@RequestMapping(ManageAgentMcpClientController.PATH)
@RequiredArgsConstructor
@Tag(name = "MCP OAuth", description = "Consent for external MCP clients connecting to an agent")
public class ManageAgentMcpClientController {

    public static final String PATH = "/manage/agents/{agentId}/mcp-clients";

    private final McpGrantService grantService;

    @Operation(summary = "List connected MCP clients", description = "One entry per consent, newest first")
    @GetMapping("/")
    public SuccessResponse<List<McpGrantView>> list(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(grantService.list(userId, agentId));
    }

    @Operation(summary = "Disconnect an MCP client", description = "Its tokens stop working at once; the agent's key is untouched")
    @DeleteMapping("/{grantId}")
    public SuccessResponse<Void> revoke(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @PathVariable UUID grantId
    ) {
        UUID userId = UUID.fromString(principal.id());
        grantService.revoke(userId, agentId, grantId);
        return SuccessResponse.empty();
    }
}
