package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.deviceapi.abac.AccessEffect;
import ru.agimate.deviceapi.abac.AccessPolicy;
import ru.agimate.deviceapi.abac.AccessPolicyService;
import ru.agimate.deviceapi.controller.manage.dto.AccessPolicyResponse;
import ru.agimate.deviceapi.controller.manage.dto.CreateAccessPolicyRequest;
import ru.agimate.deviceapi.controller.manage.dto.UpdateAccessPolicyRequest;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageAccessPolicyController.PATH)
@RequiredArgsConstructor
@Tag(name = "Access Policies", description = "Manage ABAC access policies")
public class ManageAccessPolicyController {

    public static final String PATH = "/manage/access-policies";

    private final AccessPolicyService accessPolicyService;

    @Operation(summary = "Get policies for an agent")
    @GetMapping("/")
    public SuccessResponse<List<AccessPolicyResponse>> getPolicies(
            @RequestParam String agentName
    ) {
        List<AccessPolicy> policies = accessPolicyService.getPoliciesByAgent(agentName);
        List<AccessPolicyResponse> response = policies.stream()
                .map(AccessPolicyResponse::from)
                .toList();
        return SuccessResponse.ok(response);
    }

    @Operation(summary = "Get a specific access policy")
    @GetMapping("/{policyId}")
    public SuccessResponse<AccessPolicyResponse> getPolicy(
            @PathVariable UUID policyId
    ) {
        AccessPolicy policy = accessPolicyService.getPolicyById(policyId);
        return SuccessResponse.ok(AccessPolicyResponse.from(policy));
    }

    @Operation(summary = "Create an access policy")
    @PostMapping("/")
    public SuccessResponse<AccessPolicyResponse> createPolicy(
            @Valid @RequestBody CreateAccessPolicyRequest request
    ) {
        AccessPolicy policy = accessPolicyService.createPolicy(
                request.agentName(),
                request.connectorName(),
                request.connectorIdentity(),
                request.toolName(),
                AccessEffect.valueOf(request.effect()),
                request.priority(),
                request.description()
        );
        return SuccessResponse.ok(AccessPolicyResponse.from(policy));
    }

    @Operation(summary = "Update an access policy")
    @PutMapping("/{policyId}")
    public SuccessResponse<AccessPolicyResponse> updatePolicy(
            @PathVariable UUID policyId,
            @Valid @RequestBody UpdateAccessPolicyRequest request
    ) {
        AccessPolicy policy = accessPolicyService.updatePolicy(
                policyId,
                request.connectorName(),
                request.connectorIdentity(),
                request.toolName(),
                request.effect() != null ? AccessEffect.valueOf(request.effect()) : null,
                request.priority(),
                request.description()
        );
        return SuccessResponse.ok(AccessPolicyResponse.from(policy));
    }

    @Operation(summary = "Delete an access policy")
    @DeleteMapping("/{policyId}")
    public SuccessResponse<Void> deletePolicy(
            @PathVariable UUID policyId
    ) {
        accessPolicyService.deletePolicy(policyId);
        return SuccessResponse.empty();
    }
}
