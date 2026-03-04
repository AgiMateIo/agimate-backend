package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.deviceapi.abac.AccessEffect;
import ru.agimate.deviceapi.abac.AgentToolPolicyService;
import ru.agimate.deviceapi.controller.manage.dto.AgentToolPolicyResponse;
import ru.agimate.deviceapi.controller.manage.dto.CreateAgentToolPolicyRequest;
import ru.agimate.deviceapi.controller.manage.dto.UpdateAgentToolPolicyRequest;
import ru.agimate.deviceapi.database.entities.AgentToolPolicy;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageAgentToolPolicyController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent Tool Policies", description = "Manage ABAC agent tool policies")
public class ManageAgentToolPolicyController {

    public static final String PATH = "/manage/agent-tool-policies";

    private final AgentToolPolicyService agentToolPolicyService;

    @Operation(summary = "Get tool policies for an agent")
    @GetMapping("/")
    public SuccessResponse<List<AgentToolPolicyResponse>> getPolicies(
            @RequestParam UUID apiKeyPubId
    ) {
        List<AgentToolPolicy> policies = agentToolPolicyService.getPoliciesByAgent(apiKeyPubId);
        List<AgentToolPolicyResponse> response = policies.stream()
                .map(AgentToolPolicyResponse::from)
                .toList();
        return SuccessResponse.ok(response);
    }

    @Operation(summary = "Get a specific agent tool policy")
    @GetMapping("/{policyId}")
    public SuccessResponse<AgentToolPolicyResponse> getPolicy(
            @PathVariable UUID policyId
    ) {
        AgentToolPolicy policy = agentToolPolicyService.getPolicyById(policyId);
        return SuccessResponse.ok(AgentToolPolicyResponse.from(policy));
    }

    @Operation(summary = "Create an agent tool policy")
    @PostMapping("/")
    public SuccessResponse<AgentToolPolicyResponse> createPolicy(
            @Valid @RequestBody CreateAgentToolPolicyRequest request
    ) {
        AgentToolPolicy policy = agentToolPolicyService.createPolicy(
                request.apiKeyPubId(),
                request.connectorCode(),
                request.connectorIdentity(),
                request.toolName(),
                AccessEffect.valueOf(request.effect()),
                request.priority(),
                request.description()
        );
        return SuccessResponse.ok(AgentToolPolicyResponse.from(policy));
    }

    @Operation(summary = "Update an agent tool policy")
    @PutMapping("/{policyId}")
    public SuccessResponse<AgentToolPolicyResponse> updatePolicy(
            @PathVariable UUID policyId,
            @Valid @RequestBody UpdateAgentToolPolicyRequest request
    ) {
        AgentToolPolicy policy = agentToolPolicyService.updatePolicy(
                policyId,
                request.connectorCode(),
                request.connectorIdentity(),
                request.toolName(),
                request.effect() != null ? AccessEffect.valueOf(request.effect()) : null,
                request.priority(),
                request.description()
        );
        return SuccessResponse.ok(AgentToolPolicyResponse.from(policy));
    }

    @Operation(summary = "Delete an agent tool policy")
    @DeleteMapping("/{policyId}")
    public SuccessResponse<Void> deletePolicy(
            @PathVariable UUID policyId
    ) {
        agentToolPolicyService.deletePolicy(policyId);
        return SuccessResponse.empty();
    }
}
