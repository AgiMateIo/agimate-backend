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

    public static final String PATH = "/manage/agents/{agentPubId}/skills";

    private final AgentSkillService agentSkillService;

    @Operation(summary = "List skills bound to an agent")
    @GetMapping("/")
    public SuccessResponse<Page<AgentSkillResponse>> getAgentSkills(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentPubId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agentSkillService.getAgentSkills(agentPubId, userPubId, page, size));
    }

    @Operation(summary = "Bind a skill to an agent (also creates ALLOW policies from skill connectors)")
    @PostMapping("/")
    public SuccessResponse<AgentSkillResponse> createAgentSkill(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentPubId,
            @Valid @RequestBody CreateAgentSkillRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agentSkillService.create(agentPubId, request.skillPubId(), userPubId));
    }

    @Operation(summary = "Unbind a skill from an agent (also removes unused skill-sourced policies)")
    @DeleteMapping("/{skillPubId}")
    public SuccessResponse<Void> deleteAgentSkill(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentPubId,
            @PathVariable UUID skillPubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        agentSkillService.delete(agentPubId, skillPubId, userPubId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Preview policy changes for add, remove, or sync action")
    @GetMapping("/{skillPubId}/policy-diff")
    public SuccessResponse<PolicyDiffResponse> previewPolicyDiff(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentPubId,
            @PathVariable UUID skillPubId,
            @RequestParam String action
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agentSkillService.previewPolicyDiff(agentPubId, skillPubId, userPubId, action));
    }

    @Operation(summary = "Re-sync all skill-sourced policies for the agent")
    @PostMapping("/sync-policies")
    public SuccessResponse<Void> syncPolicies(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentPubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        agentSkillService.syncPolicies(agentPubId, userPubId);
        return SuccessResponse.empty();
    }
}
