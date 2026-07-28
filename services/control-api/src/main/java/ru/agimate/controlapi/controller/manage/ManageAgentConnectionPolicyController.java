package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.abac.AgentConnectionPolicyService;
import ru.agimate.controlapi.controller.manage.dto.AgentConnectionPolicyResponse;
import ru.agimate.controlapi.controller.manage.dto.CreateAgentConnectionPolicyRequest;
import ru.agimate.controlapi.controller.manage.dto.UpdateAgentConnectionPolicyRequest;

import java.util.List;
import java.util.UUID;

/**
 * Access refinement rules on top of a binding ({@code agent_connection_policies}). The model is
 * default-allow: given a binding, a tool or trigger is permitted unless a rule says otherwise. The
 * precedence: an exact name > binding-wide ({@code name=null}) > default-allow.
 */
@RestController
@RequestMapping(ManageAgentConnectionPolicyController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent Connection Policies", description = "Refine tool/trigger access over a binding")
public class ManageAgentConnectionPolicyController {

    public static final String PATH = "/manage/agent-connections/{agentConnectionId}/policies";

    private final AgentConnectionPolicyService policyService;

    @Operation(summary = "List access rules for a binding")
    @GetMapping("/")
    public SuccessResponse<List<AgentConnectionPolicyResponse>> list(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentConnectionId) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(policyService.getPolicies(userId, agentConnectionId).stream()
                .map(AgentConnectionPolicyResponse::from)
                .toList());
    }

    @Operation(summary = "Create an access rule (ALLOW/DENY, optional params filter)")
    @PostMapping("/")
    public SuccessResponse<AgentConnectionPolicyResponse> create(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentConnectionId,
            @Valid @RequestBody CreateAgentConnectionPolicyRequest request) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(AgentConnectionPolicyResponse.from(policyService.create(
                userId, agentConnectionId, request.kind(), request.name(),
                request.effect(), request.paramsFilter(), request.description())));
    }

    @Operation(summary = "Update an access rule")
    @PatchMapping("/{policyId}")
    public SuccessResponse<AgentConnectionPolicyResponse> update(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentConnectionId,
            @PathVariable UUID policyId,
            @RequestBody UpdateAgentConnectionPolicyRequest request) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(AgentConnectionPolicyResponse.from(policyService.update(
                userId, agentConnectionId, policyId, request.effect(), request.paramsFilter(), request.description())));
    }

    @Operation(summary = "Delete an access rule")
    @DeleteMapping("/{policyId}")
    public SuccessResponse<Void> delete(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentConnectionId,
            @PathVariable UUID policyId) {
        UUID userId = UUID.fromString(principal.id());
        policyService.delete(userId, agentConnectionId, policyId);
        return SuccessResponse.empty();
    }
}
