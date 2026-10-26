package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.AgentStartersResponse;
import ru.agimate.controlapi.controller.manage.dto.HideAgentStarterRequest;
import ru.agimate.controlapi.controller.manage.dto.SaveAgentStartersRequest;
import ru.agimate.controlapi.service.AgentStarterService;

import java.util.UUID;

@RestController
@RequestMapping(ManageAgentStarterController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent Starters", description = "The greeting and the conversation starters shown above an empty chat")
public class ManageAgentStarterController {

    public static final String PATH = "/manage/agents/{agentId}/starters";

    private final AgentStarterService agentStarterService;

    @Operation(summary = "The greeting and the visible starters: the agent's own, then its skills'")
    @GetMapping("/")
    public SuccessResponse<AgentStartersResponse> getStarters(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(AgentStartersResponse.from(agentStarterService.get(agentId, userId)));
    }

    @Operation(summary = "Replace the greeting and the agent's own starters")
    @PutMapping("/")
    public SuccessResponse<AgentStartersResponse> saveStarters(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @Valid @RequestBody SaveAgentStartersRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(AgentStartersResponse.from(agentStarterService.save(
                agentId, userId, request.greeting(), request.resolveStarters())));
    }

    @Operation(summary = "Hide a skill's starter on this agent")
    @PostMapping("/hidden")
    public SuccessResponse<Void> hideStarter(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @Valid @RequestBody HideAgentStarterRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        agentStarterService.hide(agentId, userId, request.agentSkillId(), request.text());
        return SuccessResponse.empty();
    }

    @Operation(summary = "Bring back every hidden skill starter of the agent")
    @DeleteMapping("/hidden/")
    public SuccessResponse<Void> restoreHidden(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId
    ) {
        UUID userId = UUID.fromString(principal.id());
        agentStarterService.restoreHidden(agentId, userId);
        return SuccessResponse.empty();
    }
}
