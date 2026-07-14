package ru.agimate.controlapi.database.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Persisted (JSONB) and exposed-via-API description of one model returned by
 * an LLM provider's "list models" endpoint.
 */
@Schema(description = "Single model entry as reported by the LLM provider")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmModelInfo(
        @Schema(description = "Provider-specific model identifier (e.g. \"gpt-4o-mini\")")
        String id,

        @Schema(description = "Human-readable name (when the provider supplies one; null otherwise)")
        String displayName
) {
}
