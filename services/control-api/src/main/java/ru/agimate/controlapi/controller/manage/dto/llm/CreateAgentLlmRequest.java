package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import ru.agimate.controlapi.database.enums.LlmPurpose;

import java.util.UUID;

@Schema(description = "Append one LLM provider+model binding to the end of a purpose's chain")
public record CreateAgentLlmRequest(
        @NotNull
        @Schema(description = "LLM provider public ID")
        UUID llmProviderId,

        @NotBlank
        @Schema(description = "Model name (must exist in the provider's model registry if it is non-empty; "
                + "UNAVAILABLE rows count — the status is advisory)")
        String model,

        @Schema(description = "Binding role (default CHAT). CHAT — the agent-loop model; "
                + "IMAGE/VISION/AUDIO_IN/AUDIO_OUT — media model-as-tool bindings; ROUTINE — the "
                + "platform's chores (conversation compaction, session titles), falls back to CHAT")
        LlmPurpose purpose
) {
}
