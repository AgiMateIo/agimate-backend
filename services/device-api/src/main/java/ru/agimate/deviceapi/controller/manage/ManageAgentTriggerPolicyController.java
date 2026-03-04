package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.deviceapi.abac.AccessEffect;
import ru.agimate.deviceapi.abac.AgentTriggerPolicyService;
import ru.agimate.deviceapi.controller.manage.dto.AgentTriggerPolicyResponse;
import ru.agimate.deviceapi.controller.manage.dto.CreateAgentTriggerPolicyRequest;
import ru.agimate.deviceapi.controller.manage.dto.UpdateAgentTriggerPolicyRequest;
import ru.agimate.deviceapi.database.entities.AgentTriggerPolicy;

import java.util.List;
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
    public SuccessResponse<List<AgentTriggerPolicyResponse>> getPolicies(
            @RequestParam UUID apiKeyPubId
    ) {
        List<AgentTriggerPolicy> policies = agentTriggerPolicyService.getPoliciesByAgent(apiKeyPubId);
        List<AgentTriggerPolicyResponse> response = policies.stream()
                .map(AgentTriggerPolicyResponse::from)
                .toList();
        return SuccessResponse.ok(response);
    }

    @Operation(summary = "Get a specific agent trigger policy")
    @GetMapping("/{policyId}")
    public SuccessResponse<AgentTriggerPolicyResponse> getPolicy(
            @PathVariable UUID policyId
    ) {
        AgentTriggerPolicy policy = agentTriggerPolicyService.getPolicyById(policyId);
        return SuccessResponse.ok(AgentTriggerPolicyResponse.from(policy));
    }

    @Operation(summary = "Create an agent trigger policy")
    @PostMapping("/")
    public SuccessResponse<AgentTriggerPolicyResponse> createPolicy(
            @Valid @RequestBody CreateAgentTriggerPolicyRequest request
    ) {
        AgentTriggerPolicy policy = agentTriggerPolicyService.createPolicy(
                request.apiKeyPubId(),
                request.connectorName(),
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
            @PathVariable UUID policyId,
            @Valid @RequestBody UpdateAgentTriggerPolicyRequest request
    ) {
        AgentTriggerPolicy policy = agentTriggerPolicyService.updatePolicy(
                policyId,
                request.connectorName(),
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
            @PathVariable UUID policyId
    ) {
        agentTriggerPolicyService.deletePolicy(policyId);
        return SuccessResponse.empty();
    }
}
