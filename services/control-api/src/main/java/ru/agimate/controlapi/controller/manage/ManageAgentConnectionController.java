package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.AgentConnectionResponse;
import ru.agimate.controlapi.controller.manage.dto.BindConnectionRequest;
import ru.agimate.controlapi.service.AgentSkillService;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The «this connection is available to this agent» bindings ({@code agent_connections}) — the
 * availability gate for <b>external</b> instances (telegram/mcp/app). Internal connectors
 * (board/memory/time/media) are managed by skills ({@code AgentSkillPolicyService}), and webchat/acp by
 * their own services; their bindings are neither created nor removed here. A binding's tools and
 * triggers are refined through {@link ManageAgentConnectionPolicyController}.
 */
@RestController
@RequestMapping(ManageAgentConnectionController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent Connections", description = "Bind/unbind connector instances to an agent")
public class ManageAgentConnectionController {

    public static final String PATH = "/manage/agents/{agentId}/connections";

    private final ConnectionBindingService bindingService;
    private final AgentSkillService agentSkillService;

    @Operation(summary = "List connectors bound to an agent")
    @GetMapping("/")
    public SuccessResponse<List<AgentConnectionResponse>> list(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId) {
        UUID userId = UUID.fromString(principal.id());
        // Two areas meet on one screen: the binding says «allowed», the skills say «used by» — the
        // counter is what tells the user what an unbind would break.
        Map<UUID, Long> usedBySkills = agentSkillService.skillReferencesByConnection(agentId);
        return SuccessResponse.ok(bindingService.listForAgent(userId, agentId).stream()
                .map(view -> AgentConnectionResponse.from(view,
                        usedBySkills.getOrDefault(view.connection().getId(), 0L)))
                .toList());
    }

    @Operation(summary = "Bind an external connection instance to an agent")
    @PostMapping("/")
    public SuccessResponse<AgentConnectionResponse> bind(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @Valid @RequestBody BindConnectionRequest request) {
        UUID userId = UUID.fromString(principal.id());
        var view = bindingService.bindAndView(userId, agentId, request.connectionId());
        long usedBySkills = agentSkillService.skillReferencesByConnection(agentId)
                .getOrDefault(view.connection().getId(), 0L);
        return SuccessResponse.ok(AgentConnectionResponse.from(view, usedBySkills));
    }

    @Operation(summary = "Unbind an external connection instance from an agent")
    @DeleteMapping("/{connectionId}")
    public SuccessResponse<Void> unbind(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @PathVariable UUID connectionId) {
        UUID userId = UUID.fromString(principal.id());
        bindingService.unbind(userId, agentId, connectionId);
        return SuccessResponse.empty();
    }
}
