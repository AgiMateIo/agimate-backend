package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.AgentViewContentResponse;
import ru.agimate.controlapi.controller.manage.dto.AgentViewResponse;
import ru.agimate.controlapi.controller.manage.dto.ViewToolCallRequest;
import ru.agimate.controlapi.controller.manage.dto.ViewToolCallResponse;
import ru.agimate.controlapi.service.view.AgentViewService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageAgentViewController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent Views", description = "Connector views (MCP Apps) available to an agent")
public class ManageAgentViewController {

    public static final String PATH = "/manage/agents/{agentId}/views";

    private final AgentViewService agentViewService;

    @Operation(summary = "List views the agent's bound connections provide, after its access rules")
    @GetMapping("/")
    public SuccessResponse<List<AgentViewResponse>> getViews(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId
    ) {
        return SuccessResponse.ok(agentViewService.list(agentId, UUID.fromString(principal.id())));
    }

    @Operation(summary = "Read a view page to render in a sandboxed iframe",
            description = "The uri must be linked from a tool of this connection the agent may use; "
                    + "502 when the server fails to serve it")
    @GetMapping("/content")
    public SuccessResponse<AgentViewContentResponse> getViewContent(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @RequestParam UUID connectionId,
            @RequestParam String uri
    ) {
        return SuccessResponse.ok(agentViewService.content(agentId, UUID.fromString(principal.id()), connectionId, uri));
    }

    @Operation(summary = "Call a tool on behalf of the agent from an open view",
            description = "Tools of a connection serving views, unless declared for the model only. Tool errors, policy refusals "
                    + "and timeouts come back as isError with 200 — the view shows them")
    @PostMapping("/tools/call")
    public SuccessResponse<ViewToolCallResponse> callTool(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @Valid @RequestBody ViewToolCallRequest request
    ) {
        return SuccessResponse.ok(agentViewService.call(agentId, UUID.fromString(principal.id()), request));
    }
}
