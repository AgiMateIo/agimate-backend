package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.AgentSkillResponse;
import ru.agimate.controlapi.controller.manage.dto.CreateAgentSkillRequest;
import ru.agimate.controlapi.controller.manage.dto.PolicyDiffResponse;
import ru.agimate.controlapi.service.AgentSkillService;

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
    public SuccessResponse<Page<AgentSkillResponse>> getAgentSkills(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(agentSkillService.getAgentSkills(agentId, userId, page, size));
    }

    @Operation(summary = "Bind an own or public skill to an agent (also binds the skill's connectors)")
    @PostMapping("/")
    public SuccessResponse<AgentSkillResponse> createAgentSkill(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @Valid @RequestBody CreateAgentSkillRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(agentSkillService.create(agentId, request.skillId(), userId));
    }

    @Operation(summary = "Unbind a skill from an agent (connector bindings are add-only, not revoked)")
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

    @Operation(summary = "Preview policy changes for add, remove, or sync action")
    @GetMapping("/{skillId}/policy-diff")
    public SuccessResponse<PolicyDiffResponse> previewPolicyDiff(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @PathVariable UUID skillId,
            @RequestParam String action
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(agentSkillService.previewPolicyDiff(agentId, skillId, userId, action));
    }

    @Operation(summary = "Re-sync all skill-sourced policies for the agent")
    @PostMapping("/sync-policies")
    public SuccessResponse<Void> syncPolicies(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId
    ) {
        UUID userId = UUID.fromString(principal.id());
        agentSkillService.syncPolicies(agentId, userId);
        return SuccessResponse.empty();
    }
}
