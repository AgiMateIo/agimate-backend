package ru.agimate.controlapi.database.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Description of one model returned by an LLM provider's "list models" endpoint.
 * Metadata fields are opportunistic: filled when the provider exposes them (OpenRouter-style
 * {@code context_length} / {@code architecture.input_modalities} / {@code supported_parameters}),
 * {@code null} otherwise. Persisted as rows of {@code llm_provider_models} (see refreshModels).
 */
@Schema(description = "Single model entry as reported by the LLM provider")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmModelInfo(
        @Schema(description = "Provider-specific model identifier (e.g. \"gpt-4o-mini\")")
        String id,

        @Schema(description = "Human-readable name (when the provider supplies one; null otherwise)")
        String displayName,

        @Schema(description = "Context window in tokens (when the provider supplies it)")
        Integer contextWindow,

        @Schema(description = "Input modalities, e.g. [\"text\",\"image\"] (when the provider supplies them)")
        List<String> inputModalities,

        @Schema(description = "Supported request parameters, e.g. [\"tools\",\"reasoning\"] "
                + "(when the provider supplies them)")
        List<String> supportedParameters
) {
    public LlmModelInfo(String id, String displayName) {
        this(id, displayName, null, null, null);
    }
}
