package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "One link of a platform purpose's chain: the platform provider and the model")
public record PlatformLlmBindingRequest(
        @NotNull
        @Schema(description = "Platform LLM provider public ID (a system-owned provider)")
        UUID llmProviderId,

        @NotBlank
        @Schema(description = "Model name (must exist in the provider's model registry if it is non-empty)")
        String model
) {
}
