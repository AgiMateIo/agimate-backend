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
import ru.agimate.deviceapi.abac.AgentToolPolicyService;
import ru.agimate.deviceapi.controller.manage.dto.AgentToolPolicyResponse;
import ru.agimate.deviceapi.controller.manage.dto.CreateAgentToolPolicyRequest;
import ru.agimate.deviceapi.controller.manage.dto.UpdateAgentToolPolicyRequest;
import ru.agimate.deviceapi.database.entities.AgentToolPolicy;

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
    public SuccessResponse<Page<AgentToolPolicyResponse>> getPolicies(
            @AuthenticationPrincipal AgimateUserPrincipal userPrincipal,
            @RequestParam UUID agentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = UUID.fromString(userPrincipal.id());
        Page<AgentToolPolicyResponse> response = agentToolPolicyService.getPoliciesByAgent(userId, agentId, page, size)
                .map(AgentToolPolicyResponse::from);
        return SuccessResponse.ok(response);
    }

    @Operation(summary = "Get a specific agent tool policy")
    @GetMapping("/{policyId}")
    public SuccessResponse<AgentToolPolicyResponse> getPolicy(
            @AuthenticationPrincipal AgimateUserPrincipal userPrincipal,
            @PathVariable UUID policyId
    ) {
        UUID userId = UUID.fromString(userPrincipal.id());
        AgentToolPolicy policy = agentToolPolicyService.getPolicyById(userId, policyId);
        return SuccessResponse.ok(AgentToolPolicyResponse.from(policy));
    }

    @Operation(summary = "Create an agent tool policy")
    @PostMapping("/")
    public SuccessResponse<AgentToolPolicyResponse> createPolicy(
            @AuthenticationPrincipal AgimateUserPrincipal userPrincipal,
            @Valid @RequestBody CreateAgentToolPolicyRequest request
    ) {
        UUID userId = UUID.fromString(userPrincipal.id());
        AgentToolPolicy policy = agentToolPolicyService.createPolicy(
                userId,
                request.agentId(),
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
            @AuthenticationPrincipal AgimateUserPrincipal userPrincipal,
            @PathVariable UUID policyId,
            @Valid @RequestBody UpdateAgentToolPolicyRequest request
    ) {
        UUID userId = UUID.fromString(userPrincipal.id());
        AgentToolPolicy policy = agentToolPolicyService.updatePolicy(
                userId,
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
            @AuthenticationPrincipal AgimateUserPrincipal userPrincipal,
            @PathVariable UUID policyId
    ) {
        UUID userId = UUID.fromString(userPrincipal.id());
        agentToolPolicyService.deletePolicy(userId, policyId);
        return SuccessResponse.empty();
    }
}
