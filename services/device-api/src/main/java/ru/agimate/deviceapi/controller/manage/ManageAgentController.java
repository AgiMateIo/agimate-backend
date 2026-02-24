package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.manage.dto.AgentResponse;
import ru.agimate.deviceapi.controller.manage.dto.CreateAgentRequest;
import ru.agimate.deviceapi.controller.manage.dto.UpdateAgentRequest;
import ru.agimate.deviceapi.service.AgentService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageAgentController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agents", description = "Manage agents")
public class ManageAgentController {

    public static final String PATH = "/manage/agents";

    private final AgentService agentService;

    @Operation(summary = "List agents for the current user")
    @GetMapping("/")
    public SuccessResponse<List<AgentResponse>> getAgents(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(required = false) UUID agenticTeamPubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agentService.getAllForUser(userPubId, agenticTeamPubId));
    }

    @Operation(summary = "Create an agent")
    @PostMapping("/")
    public SuccessResponse<AgentResponse> createAgent(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateAgentRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agentService.create(userPubId, request));
    }

    @Operation(summary = "Get agent by API key")
    @GetMapping("/{apiKeyPubId}")
    public SuccessResponse<AgentResponse> getAgentById(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID apiKeyPubId
    ) {
        return SuccessResponse.ok(agentService.getByApiKeyPubId(apiKeyPubId));
    }

    @Operation(summary = "Update an agent")
    @PutMapping("/{apiKeyPubId}")
    public SuccessResponse<AgentResponse> updateAgent(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID apiKeyPubId,
            @Valid @RequestBody UpdateAgentRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agentService.update(apiKeyPubId, userPubId, request));
    }

    @Operation(summary = "Delete an agent")
    @DeleteMapping("/{apiKeyPubId}")
    public SuccessResponse<Void> deleteAgent(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID apiKeyPubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        agentService.delete(apiKeyPubId, userPubId);
        return SuccessResponse.empty();
    }
}
