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
import ru.agimate.deviceapi.abac.AccessEffect;
import ru.agimate.deviceapi.abac.AgentTriggerPolicyService;
import ru.agimate.deviceapi.controller.manage.dto.AgentTriggerPolicyResponse;
import ru.agimate.deviceapi.controller.manage.dto.CreateAgentTriggerPolicyRequest;
import ru.agimate.deviceapi.controller.manage.dto.UpdateAgentTriggerPolicyRequest;
import ru.agimate.deviceapi.database.entities.AgentTriggerPolicy;

import java.util.UUID;

@RestController
@RequestMapping(ManageAgentTriggerPolicyController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent Trigger Policies", description = "Manage ABAC agent trigger policies")
public class ManageAgentTriggerPolicyController {

    public static final String PATH = "/manage/agent-trigger-policies";

    private final AgentTriggerPolicyService agentTriggerPolicyService;

    @Operation(summary = "Get trigger policies for an agent")
    @GetMapping("/")
    public SuccessResponse<Page<AgentTriggerPolicyResponse>> getPolicies(
            @AuthenticationPrincipal AgimateUserPrincipal userPrincipal,
            @RequestParam UUID agentPubId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userPubId = UUID.fromString(userPrincipal.pubId());
        Page<AgentTriggerPolicyResponse> response = agentTriggerPolicyService.getPoliciesByAgent(userPubId, agentPubId, page, size)
                .map(AgentTriggerPolicyResponse::from);
        return SuccessResponse.ok(response);
    }

    @Operation(summary = "Get a specific agent trigger policy")
    @GetMapping("/{policyId}")
    public SuccessResponse<AgentTriggerPolicyResponse> getPolicy(
            @AuthenticationPrincipal AgimateUserPrincipal userPrincipal,
            @PathVariable UUID policyId
    ) {
        UUID userPubId = UUID.fromString(userPrincipal.pubId());
        AgentTriggerPolicy policy = agentTriggerPolicyService.getPolicyById(userPubId, policyId);
        return SuccessResponse.ok(AgentTriggerPolicyResponse.from(policy));
    }

    @Operation(summary = "Create an agent trigger policy")
    @PostMapping("/")
    public SuccessResponse<AgentTriggerPolicyResponse> createPolicy(
            @AuthenticationPrincipal AgimateUserPrincipal userPrincipal,
            @Valid @RequestBody CreateAgentTriggerPolicyRequest request
    ) {
        UUID userPubId = UUID.fromString(userPrincipal.pubId());
        AgentTriggerPolicy policy = agentTriggerPolicyService.createPolicy(
                userPubId,
                request.agentPubId(),
                request.connectorCode(),
                request.connectorIdentity(),
                request.triggerName(),
                AccessEffect.valueOf(request.effect()),
                request.priority(),
                request.description()
        );
        return SuccessResponse.ok(AgentTriggerPolicyResponse.from(policy));
    }

    @Operation(summary = "Update an agent trigger policy")
    @PutMapping("/{policyId}")
    public SuccessResponse<AgentTriggerPolicyResponse> updatePolicy(
            @AuthenticationPrincipal AgimateUserPrincipal userPrincipal,
            @PathVariable UUID policyId,
            @Valid @RequestBody UpdateAgentTriggerPolicyRequest request
    ) {
        UUID userPubId = UUID.fromString(userPrincipal.pubId());
        AgentTriggerPolicy policy = agentTriggerPolicyService.updatePolicy(
                userPubId,
                policyId,
                request.connectorCode(),
                request.connectorIdentity(),
                request.triggerName(),
                request.effect() != null ? AccessEffect.valueOf(request.effect()) : null,
                request.priority(),
                request.description()
        );
        return SuccessResponse.ok(AgentTriggerPolicyResponse.from(policy));
    }

    @Operation(summary = "Delete an agent trigger policy")
    @DeleteMapping("/{policyId}")
    public SuccessResponse<Void> deletePolicy(
            @AuthenticationPrincipal AgimateUserPrincipal userPrincipal,
            @PathVariable UUID policyId
    ) {
        UUID userPubId = UUID.fromString(userPrincipal.pubId());
        agentTriggerPolicyService.deletePolicy(userPubId, policyId);
        return SuccessResponse.empty();
    }
}
