package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.enums.LlmPurpose;

import java.util.List;
import java.util.Map;

@Schema(description = "Request to update an LLM provider configuration (partial update)")
public record UpdateLlmProviderRequest(
        @Schema(description = "New name")
        String name,

        @Schema(description = "New base URL")
        String baseUrl,

        @Schema(description = "New API key. If null/absent — existing key is kept; if present — re-encrypted")
        String apiKey,

        @Schema(description = "Models allowed per purpose, in priority order; the whole map is "
                + "replaced, an empty object clears it, absent — kept")
        Map<LlmPurpose, List<String>> purposePriority,

        @Schema(description = "Provider-level extra chat/completions body fields; empty object "
                + "clears it, absent — kept")
        Map<String, Object> extraBody,

        @Schema(description = "Whether the provider is enabled")
        Boolean enabled
) {
}
