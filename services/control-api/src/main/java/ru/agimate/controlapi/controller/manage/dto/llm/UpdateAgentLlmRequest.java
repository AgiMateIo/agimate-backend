package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.agimate.controlapi.service.AgentLlmService;

import java.util.List;

@Schema(description = "Replace the ordered chain of bindings for a purpose (the purpose is in the path)")
public record UpdateAgentLlmRequest(
        @NotNull
        @Size(max = AgentLlmService.MAX_BINDINGS_PER_PURPOSE)
        @Schema(description = "Bindings in priority order, at most five; an empty list removes the "
                + "purpose and is equivalent to DELETE")
        List<@Valid AgentLlmBindingRequest> bindings
) {
}
