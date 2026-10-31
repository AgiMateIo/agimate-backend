package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import ru.agimate.controlapi.database.enums.LlmProviderType;

/**
 * Creation of a platform (free-tier) provider. The name is free and unique among platform providers
 * — the platform may run several vendors. {@code enabled} is ignored (the row is created disabled),
 * and {@code purposePriority} is not accepted: platform purposes live in {@code platform_llms}.
 */
@Schema(description = "Create a platform LLM provider (ADMIN); created disabled, purposes are "
        + "configured separately")
public record CreatePlatformLlmProviderRequest(
        @NotBlank
        @Schema(description = "Human-readable name, unique among platform providers")
        String name,

        @NotNull
        @Schema(description = "Provider type")
        LlmProviderType providerType,

        @Schema(description = "Custom base URL (required for OPENAI_COMPATIBLE, optional for others)")
        String baseUrl,

        @NotBlank
        @Schema(description = "API key — encrypted at rest, never returned in responses")
        String apiKey
) {
}
