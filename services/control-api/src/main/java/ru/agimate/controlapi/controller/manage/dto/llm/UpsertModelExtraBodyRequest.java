package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

@Schema(description = "Set (or clear) per-model extra_body of an LLM provider model. Upserts the "
        + "registry row: config may be added for a model the provider hasn't listed yet")
public record UpsertModelExtraBodyRequest(
        @NotBlank
        @Schema(description = "Provider-specific model identifier (e.g. \"moonshotai/kimi-k2.5\")")
        String model,

        @Schema(description = "Extra chat/completions body fields for this model "
                + "(e.g. OpenRouter {\"provider\":{\"only\":[...]}}); null clears the override. "
                + "Not a secret store — do not put API keys here")
        Map<String, Object> extraBody
) {
}
