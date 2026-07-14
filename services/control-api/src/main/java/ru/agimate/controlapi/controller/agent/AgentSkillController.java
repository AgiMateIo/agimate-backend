package ru.agimate.controlapi.controller.agent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.PageResponse;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.controlapi.controller.agent.dto.AgentSkillWithConnectorsResponse;
import ru.agimate.controlapi.security.AgentPrincipal;
import ru.agimate.controlapi.service.AgentSkillService;

@RestController
@RequestMapping(AgentSkillController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent Skills", description = "Agent access to skills via API Key")
public class AgentSkillController {

    public static final String PATH = "/agent/skills";

    private final AgentSkillService agentSkillService;

    @Operation(summary = "List skills assigned to this agent")
    @GetMapping("/")
    public SuccessResponse<PageResponse<AgentSkillWithConnectorsResponse>> getSkills(
            @AuthenticationPrincipal AgentPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return SuccessResponse.ok(PageResponse.from(agentSkillService.getAgentSkillsWithConnectors(principal.agentId(), principal.userId(), page, size)));
    }
}
