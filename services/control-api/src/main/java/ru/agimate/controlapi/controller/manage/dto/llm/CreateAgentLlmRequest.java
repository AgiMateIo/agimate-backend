package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import ru.agimate.controlapi.database.enums.LlmPurpose;

import java.util.UUID;

@Schema(description = "Request to bind an LLM provider+model to an agent for a given purpose")
public record CreateAgentLlmRequest(
        @NotNull
        @Schema(description = "LLM provider public ID")
        UUID llmProviderId,

        @NotBlank
        @Schema(description = "Model name (must exist in the provider's model registry if it is non-empty; "
                + "UNAVAILABLE rows count — the status is advisory)")
        String model,

        @Schema(description = "Binding role (default CHAT), unique per agent. CHAT — the agent-loop "
                + "model; IMAGE/VISION/AUDIO_IN/AUDIO_OUT — media model-as-tool bindings, override "
                + "the capability-based auto-match")
        LlmPurpose purpose
) {
}
