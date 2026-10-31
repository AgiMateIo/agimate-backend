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
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.security.AgentPrincipal;
import ru.agimate.controlapi.service.AgentLlmService;

import java.util.List;

@RestController
@RequestMapping(AgentLlmController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent LLM runtime", description = "Returns the LLM binding of the authenticated agent")
public class AgentLlmController {

    public static final String PATH = "/agent/llm";

    private final AgentLlmService agentLlmService;

    @Operation(
            summary = "List the active BYOK LLM bindings of the current agent",
            description = "Returns the active own bindings of the current agent — model metadata, "
                    + "without credentials. A PLATFORM agent has none: the list is empty, because "
                    + "platform configuration is not exposed"
    )
    @GetMapping("/")
    public SuccessResponse<List<AgentLlmRuntimeResponse>> list(
            @AuthenticationPrincipal AgentPrincipal principal
    ) {
        return SuccessResponse.ok(agentLlmService.runtimeForAgent(principal.agentId()));
    }

    @Operation(
            summary = "Get the active BYOK LLM binding by purpose",
            description = "Returns the first enabled own binding for the given purpose (e.g. CHAT). "
                    + "A PLATFORM agent answers 404: platform configuration is not exposed"
    )
    @GetMapping("/{purpose}")
    public SuccessResponse<AgentLlmRuntimeResponse> getByPurpose(
            @AuthenticationPrincipal AgentPrincipal principal,
            @PathVariable LlmPurpose purpose
    ) {
        return SuccessResponse.ok(agentLlmService.runtimeForAgentByPurpose(principal.agentId(), purpose));
    }
}
