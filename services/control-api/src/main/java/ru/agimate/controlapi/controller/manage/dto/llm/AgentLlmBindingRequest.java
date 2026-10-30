package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "One link of a purpose's chain: the provider and the model it contributes")
public record AgentLlmBindingRequest(
        @NotNull
        @Schema(description = "LLM provider public ID (must belong to the caller)")
        UUID llmProviderId,

        @NotBlank
        @Schema(description = "Model name (must exist in the provider's model registry if it is non-empty)")
        String model
) {
}
