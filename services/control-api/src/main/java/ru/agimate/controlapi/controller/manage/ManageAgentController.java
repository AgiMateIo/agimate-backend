package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.AgentCreatedResponse;
import ru.agimate.controlapi.controller.manage.dto.AgentResponse;
import ru.agimate.controlapi.controller.manage.dto.CreateAgentRequest;
import ru.agimate.controlapi.controller.manage.dto.UpdateAgentRequest;
import ru.agimate.controlapi.service.AgentService;

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
    public SuccessResponse<Page<AgentResponse>> getAgents(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(required = false) UUID agenticTeamId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(agentService.getAllForUser(userId, agenticTeamId, search, page, size));
    }

    @Operation(summary = "Create an agent")
    @PostMapping("/")
    public SuccessResponse<AgentCreatedResponse> createAgent(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateAgentRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        var result = agentService.create(userId, request);
        return SuccessResponse.ok(new AgentCreatedResponse(
                AgentResponse.from(result.agent(), result.team()),
                result.plaintextKey()
        ));
    }

    @Operation(summary = "Get agent by id")
    @GetMapping("/{agentId}")
    public SuccessResponse<AgentResponse> getAgentById(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(agentService.getById(agentId, userId));
    }

    @Operation(summary = "Update an agent")
    @PutMapping("/{agentId}")
    public SuccessResponse<AgentResponse> updateAgent(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @Valid @RequestBody UpdateAgentRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(agentService.update(agentId, userId, request));
    }

    @Operation(summary = "Delete an agent")
    @DeleteMapping("/{agentId}")
    public SuccessResponse<Void> deleteAgent(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId
    ) {
        UUID userId = UUID.fromString(principal.id());
        agentService.delete(agentId, userId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Regenerate agent API key")
    @PostMapping("/{agentId}/regenerate")
    public SuccessResponse<AgentCreatedResponse> regenerateKey(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId
    ) {
        UUID userId = UUID.fromString(principal.id());
        var result = agentService.regenerateKey(agentId, userId);
        return SuccessResponse.ok(new AgentCreatedResponse(
                AgentResponse.from(result.agent(), result.team()),
                result.plaintextKey()
        ));
    }
}
