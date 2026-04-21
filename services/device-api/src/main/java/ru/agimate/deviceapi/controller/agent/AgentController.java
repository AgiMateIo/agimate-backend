package ru.agimate.deviceapi.controller.agent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.deviceapi.controller.agent.dto.AgentConfigResponse;
import ru.agimate.deviceapi.controller.agent.dto.AgentContextResponse;
import ru.agimate.deviceapi.security.AgentPrincipal;
import ru.agimate.deviceapi.service.AgentService;

@RestController
@RequestMapping(AgentController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent API", description = "Agent configuration via API Key")
public class AgentController {

    public static final String PATH = "/agent";

    private final AgentService agentService;

    @Operation(
            summary = "Get agent configuration",
            description = "Returns agent configuration for the authenticated API key"
    )
    @GetMapping("/settings")
    public SuccessResponse<AgentConfigResponse> getAgentSettings(
            @AuthenticationPrincipal AgentPrincipal principal
    ) {
        return SuccessResponse.ok(agentService.getConfigByPubId(principal.agentPubId()));
    }

    @Operation(
            summary = "Get agent context",
            description = "Returns information about the agent, its team, and teammates"
    )
    @GetMapping("/context")
    public SuccessResponse<AgentContextResponse> getAgentContext(
            @AuthenticationPrincipal AgentPrincipal principal
    ) {
        return SuccessResponse.ok(agentService.getContextByPubId(principal.agentPubId()));
    }
}
