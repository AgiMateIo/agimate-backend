package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Request to bind an LLM provider+model to an agent under a label")
public record CreateAgentLlmRequest(
        @NotBlank
        @Schema(description = "Label of the binding (e.g. \"main_model\", \"for_light_task\", \"visual_task\")")
        String name,

        @NotNull
        @Schema(description = "LLM provider public ID")
        UUID llmProviderId,

        @NotBlank
        @Schema(description = "Model name (must exist in provider's availableModels if list is non-empty)")
        String model
) {
}
