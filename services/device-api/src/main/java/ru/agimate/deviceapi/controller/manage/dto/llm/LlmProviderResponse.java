package ru.agimate.deviceapi.controller.manage.dto.llm;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.LlmProvider;
import ru.agimate.deviceapi.database.enums.LlmProviderType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "LLM provider configuration (api_key never exposed)")
public record LlmProviderResponse(
        @Schema(description = "Public ID")
        UUID pubId,

        @Schema(description = "Human-readable name")
        String name,

        @Schema(description = "Provider type")
        LlmProviderType providerType,

        @Schema(description = "Custom base URL")
        String baseUrl,

        @Schema(description = "Masked API key (e.g. \"sk-...AbCd\")")
        String apiKeyMask,

        @Schema(description = "Available models (refreshed via refresh-models endpoint)")
        List<String> availableModels,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When availableModels was last refreshed")
        LocalDateTime modelsRefreshedAt,

        @Schema(description = "Whether the provider is enabled")
        boolean enabled,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Created at")
        LocalDateTime createdAt
) {
    public static LlmProviderResponse from(LlmProvider provider) {
        return new LlmProviderResponse(
                provider.getPubId(),
                provider.getName(),
                provider.getProviderType(),
                provider.getBaseUrl(),
                provider.getApiKeyMask(),
                provider.getAvailableModels(),
                provider.getModelsRefreshedAt(),
                provider.isEnabled(),
                provider.getCreatedAt()
        );
    }
}
