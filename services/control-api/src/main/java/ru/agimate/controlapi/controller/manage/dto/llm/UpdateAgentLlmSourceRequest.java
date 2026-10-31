package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import ru.agimate.controlapi.database.enums.LlmSource;

@Schema(description = "Switch the agent's model source. Bindings are kept: a PLATFORM agent's own "
        + "rows become inactive, a BYOK agent's rows become active again")
public record UpdateAgentLlmSourceRequest(
        @NotNull
        @Schema(description = "PLATFORM — the platform's models pay; BYOK — the agent's own bindings")
        LlmSource source
) {
}
