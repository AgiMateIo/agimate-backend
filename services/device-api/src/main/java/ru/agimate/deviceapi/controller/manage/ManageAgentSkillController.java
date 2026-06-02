package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.manage.dto.AgentSkillResponse;
import ru.agimate.deviceapi.controller.manage.dto.CreateAgentSkillRequest;
import ru.agimate.deviceapi.controller.manage.dto.PolicyDiffResponse;
import ru.agimate.deviceapi.service.AgentSkillService;

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
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agentSkillService.getAgentSkills(agentId, userPubId, page, size));
    }

    @Operation(summary = "Bind a skill to an agent (also creates ALLOW policies from skill connectors)")
    @PostMapping("/")
    public SuccessResponse<AgentSkillResponse> createAgentSkill(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @Valid @RequestBody CreateAgentSkillRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agentSkillService.create(agentId, request.skillId(), userPubId));
    }

    @Operation(summary = "Unbind a skill from an agent (also removes unused skill-sourced policies)")
    @DeleteMapping("/{skillId}")
    public SuccessResponse<Void> deleteAgentSkill(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @PathVariable UUID skillId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        agentSkillService.delete(agentId, skillId, userPubId);
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
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agentSkillService.previewPolicyDiff(agentId, skillId, userPubId, action));
    }

    @Operation(summary = "Re-sync all skill-sourced policies for the agent")
    @PostMapping("/sync-policies")
    public SuccessResponse<Void> syncPolicies(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        agentSkillService.syncPolicies(agentId, userPubId);
        return SuccessResponse.empty();
    }
}
