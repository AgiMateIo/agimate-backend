package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.PageResponse;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.AgentSkillResponse;
import ru.agimate.controlapi.controller.manage.dto.CreateAgentSkillRequest;
import ru.agimate.controlapi.service.AgentSkillService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(ManageAgentSkillController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent Skills", description = "Manage agent-skill bindings and auto-managed policies")
public class ManageAgentSkillController {

    public static final String PATH = "/manage/agents/{agentId}/skills";

    private final AgentSkillService agentSkillService;

    @Operation(summary = "List skills bound to an agent")
    @GetMapping("/")
    public SuccessResponse<PageResponse<AgentSkillResponse>> getAgentSkills(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(PageResponse.from(agentSkillService.getAgentSkills(agentId, userId, page, size)));
    }

    @Operation(summary = "Bind a skill to an agent, declaring which instance it works with per connector")
    @PostMapping("/")
    public SuccessResponse<AgentSkillResponse> createAgentSkill(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @Valid @RequestBody CreateAgentSkillRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(agentSkillService.create(
                agentId, request.skillId(), userId, request.resolveConnections()));
    }

    @Operation(summary = "Replace the instances the skill works with (connector code → connection id)")
    @PutMapping("/{skillId}/connections")
    public SuccessResponse<AgentSkillResponse> replaceSkillConnections(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @PathVariable UUID skillId,
            @RequestBody Map<String, UUID> connections
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(agentSkillService.replaceConnections(
                agentId, skillId, userId, connections == null ? Map.of() : connections));
    }

    @Operation(summary = "Unbind a skill from an agent (the connections it used stay bound)")
    @DeleteMapping("/{skillId}")
    public SuccessResponse<Void> deleteAgentSkill(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @PathVariable UUID skillId
    ) {
        UUID userId = UUID.fromString(principal.id());
        agentSkillService.delete(agentId, skillId, userId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Accept the current version of every skill on the agent (clears needsReinstall)")
    @PostMapping("/refresh")
    public SuccessResponse<Void> refreshSkillVersions(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId
    ) {
        UUID userId = UUID.fromString(principal.id());
        agentSkillService.markSkillsInstalled(agentId, userId);
        return SuccessResponse.empty();
    }
}
