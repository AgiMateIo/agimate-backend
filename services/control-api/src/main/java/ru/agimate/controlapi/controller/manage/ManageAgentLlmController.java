package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.llm.AgentLlmResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.CreateAgentLlmRequest;
import ru.agimate.controlapi.controller.manage.dto.llm.UpdateAgentLlmRequest;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.service.AgentLlmService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageAgentLlmController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent LLM bindings", description = "Manage agent ↔ LLM provider bindings")
public class ManageAgentLlmController {

    public static final String PATH = "/manage/agents/{agentId}/llms";

    private final AgentLlmService agentLlmService;

    @Operation(summary = "List LLM bindings for the agent")
    @GetMapping("/")
    public SuccessResponse<List<AgentLlmResponse>> list(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(agentLlmService.listForAgent(agentId, userId));
    }

    @Operation(summary = "Append an LLM binding to the end of a purpose's chain")
    @PostMapping("/")
    public SuccessResponse<AgentLlmResponse> create(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @Valid @RequestBody CreateAgentLlmRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(agentLlmService.create(agentId, userId, request));
    }

    @Operation(summary = "Replace the ordered chain of bindings for the given purpose")
    @PutMapping("/{purpose}")
    public SuccessResponse<List<AgentLlmResponse>> replace(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @PathVariable LlmPurpose purpose,
            @Valid @RequestBody UpdateAgentLlmRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(agentLlmService.replaceChain(agentId, userId, purpose, request));
    }

    @Operation(summary = "Delete the LLM binding for the given purpose")
    @DeleteMapping("/{purpose}")
    public SuccessResponse<Void> delete(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID agentId,
            @PathVariable LlmPurpose purpose
    ) {
        UUID userId = UUID.fromString(principal.id());
        agentLlmService.delete(agentId, userId, purpose);
        return SuccessResponse.empty();
    }
}
