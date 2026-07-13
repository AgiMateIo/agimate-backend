package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import ru.agimate.controlapi.database.enums.LlmProviderType;

/**
 * Создание платформенного (free-tier) провайдера. Имя не принимается — оно форсируется на
 * {@code "platform"} (ключ fallback-выдачи), а {@code enabled} игнорируется (строка создаётся
 * выключенной). Поэтому отдельный DTO без {@code name}/{@code enabled}, а не общий
 * {@link CreateLlmProviderRequest}.
 */
@Schema(description = "Create the platform LLM provider (ADMIN); name is forced, created disabled")
public record CreatePlatformLlmProviderRequest(
        @NotNull
        @Schema(description = "Provider type")
        LlmProviderType providerType,

        @Schema(description = "Custom base URL (required for OPENAI_COMPATIBLE, optional for others)")
        String baseUrl,

        @NotBlank
        @Schema(description = "API key — encrypted at rest, never returned in responses")
        String apiKey,

        @Schema(description = "Default (fallback) model")
        String defaultModel
) {
}
