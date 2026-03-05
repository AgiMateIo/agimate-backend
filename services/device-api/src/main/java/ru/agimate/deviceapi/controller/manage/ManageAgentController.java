package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.manage.dto.AgentCreatedResponse;
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
    public SuccessResponse<AgentCreatedResponse> createAgent(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateAgentRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var result = agentService.create(userPubId, request);
        return SuccessResponse.ok(new AgentCreatedResponse(
                AgentResponse.from(result.agent(), result.team()),
                result.plaintextKey()
        ));
    }

    @Operation(summary = "Get agent by pubId")
    @GetMapping("/{agentPubId}")
    public SuccessResponse<AgentResponse> getAgentById(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentPubId
    ) {
        return SuccessResponse.ok(agentService.getByPubId(agentPubId));
    }

    @Operation(summary = "Update an agent")
    @PutMapping("/{id}")
    public SuccessResponse<AgentResponse> updateAgent(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAgentRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agentService.update(id, userPubId, request));
    }

    @Operation(summary = "Delete an agent")
    @DeleteMapping("/{id}")
    public SuccessResponse<Void> deleteAgent(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        agentService.delete(id, userPubId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Regenerate agent API key")
    @PostMapping("/{id}/regenerate")
    public SuccessResponse<AgentCreatedResponse> regenerateKey(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var result = agentService.regenerateKey(id, userPubId);
        return SuccessResponse.ok(new AgentCreatedResponse(
                AgentResponse.from(result.agent(), result.team()),
                result.plaintextKey()
        ));
    }
}
