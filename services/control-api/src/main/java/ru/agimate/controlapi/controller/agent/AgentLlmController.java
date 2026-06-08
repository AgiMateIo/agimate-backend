package ru.agimate.controlapi.controller.agent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.controlapi.controller.agent.dto.AgentLlmRuntimeResponse;
import ru.agimate.controlapi.security.AgentPrincipal;
import ru.agimate.controlapi.service.AgentLlmService;

import java.util.List;

@RestController
@RequestMapping(AgentLlmController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent LLM runtime", description = "Returns LLM credentials to the authenticated agent")
public class AgentLlmController {

    public static final String PATH = "/agent/llm";

    private final AgentLlmService agentLlmService;

    @Operation(
            summary = "List LLM bindings with decrypted credentials",
            description = "Returns all enabled LLM bindings of the current agent including decrypted apiKey"
    )
    @GetMapping
    public SuccessResponse<List<AgentLlmRuntimeResponse>> list(
            @AuthenticationPrincipal AgentPrincipal principal
    ) {
        return SuccessResponse.ok(agentLlmService.runtimeForAgent(principal.agentId()));
    }

    @Operation(
            summary = "Get a single LLM binding by name",
            description = "Returns the LLM binding identified by its label (e.g. main_model)"
    )
    @GetMapping("/{name}")
    public SuccessResponse<AgentLlmRuntimeResponse> getByName(
            @AuthenticationPrincipal AgentPrincipal principal,
            @PathVariable String name
    ) {
        return SuccessResponse.ok(agentLlmService.runtimeForAgentByName(principal.agentId(), name));
    }
}
