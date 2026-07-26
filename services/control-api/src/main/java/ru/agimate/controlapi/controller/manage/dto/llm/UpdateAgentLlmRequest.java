package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Replace the LLM binding identified by its purpose (the purpose is in the path)")
public record UpdateAgentLlmRequest(
        @NotNull
        @Schema(description = "LLM provider public ID")
        UUID llmProviderId,

        @NotBlank
        @Schema(description = "Model name")
        String model
) {
}
