package ru.agimate.deviceapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import ru.agimate.deviceapi.database.enums.LlmProviderType;

@Schema(description = "Request to create an LLM provider configuration")
public record CreateLlmProviderRequest(
        @NotBlank
        @Schema(description = "Human-readable name (unique per user), e.g. \"Production OpenAI\"")
        String name,

        @NotNull
        @Schema(description = "Provider type")
        LlmProviderType providerType,

        @Schema(description = "Custom base URL (required for OPENAI_COMPATIBLE, optional for others)")
        String baseUrl,

        @NotBlank
        @Schema(description = "API key — encrypted at rest, never returned in responses")
        String apiKey,

        @Schema(description = "Whether the provider is enabled")
        Boolean enabled
) {
}
