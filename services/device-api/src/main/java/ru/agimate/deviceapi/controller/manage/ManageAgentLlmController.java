package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.manage.dto.llm.AgentLlmResponse;
import ru.agimate.deviceapi.controller.manage.dto.llm.CreateAgentLlmRequest;
import ru.agimate.deviceapi.controller.manage.dto.llm.UpdateAgentLlmRequest;
import ru.agimate.deviceapi.service.AgentLlmService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageAgentLlmController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent LLM bindings", description = "Manage agent ↔ LLM provider bindings")
public class ManageAgentLlmController {

    public static final String PATH = "/manage/agents/{agentPubId}/llms";

    private final AgentLlmService agentLlmService;

    @Operation(summary = "List LLM bindings for the agent")
    @GetMapping("/")
    public SuccessResponse<List<AgentLlmResponse>> list(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentPubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agentLlmService.listForAgent(agentPubId, userPubId));
    }

    @Operation(summary = "Create an LLM binding for the agent")
    @PostMapping("/")
    public SuccessResponse<AgentLlmResponse> create(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentPubId,
            @Valid @RequestBody CreateAgentLlmRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agentLlmService.create(agentPubId, userPubId, request));
    }

    @Operation(summary = "Replace the LLM binding identified by its name")
    @PutMapping("/{name}")
    public SuccessResponse<AgentLlmResponse> replace(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentPubId,
            @PathVariable String name,
            @Valid @RequestBody UpdateAgentLlmRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agentLlmService.replace(agentPubId, userPubId, name, request));
    }

    @Operation(summary = "Delete the LLM binding identified by its name")
    @DeleteMapping("/{name}")
    public SuccessResponse<Void> delete(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentPubId,
            @PathVariable String name
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        agentLlmService.delete(agentPubId, userPubId, name);
        return SuccessResponse.empty();
    }
}
