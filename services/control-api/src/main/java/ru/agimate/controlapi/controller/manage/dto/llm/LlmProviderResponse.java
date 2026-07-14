package ru.agimate.controlapi.controller.manage.dto.llm;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.model.LlmModelInfo;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.service.SystemSkillBootstrap;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "LLM provider configuration (api_key never exposed)")
public record LlmProviderResponse(
        @Schema(description = "Public ID")
        UUID id,

        @Schema(description = "Human-readable name")
        String name,

        @Schema(description = "Provider type")
        LlmProviderType providerType,

        @Schema(description = "Custom base URL")
        String baseUrl,

        @Schema(description = "Default model (on the platform provider — the fallback model)")
        String defaultModel,

        @Schema(description = "Masked API key (e.g. \"sk-AbCd...WxYz\")")
        String apiKeyMask,

        @Schema(description = "Available models (refreshed via refresh-models endpoint)")
        List<LlmModelInfo> availableModels,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "When availableModels was last refreshed")
        LocalDateTime modelsRefreshedAt,

        @Schema(description = "Whether the provider is enabled")
        boolean enabled,

        @Schema(description = "True for the system-owned platform provider (visible to ADMIN only; "
                + "rename/delete are rejected)")
        boolean platform,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        @Schema(description = "Created at")
        LocalDateTime createdAt
) {
    public static LlmProviderResponse from(LlmProvider provider) {
        return new LlmProviderResponse(
                provider.getId(),
                provider.getName(),
                provider.getProviderType(),
                provider.getBaseUrl(),
                provider.getDefaultModel(),
                provider.getApiKeyMask(),
                provider.getAvailableModels(),
                provider.getModelsRefreshedAt(),
                provider.isEnabled(),
                SystemSkillBootstrap.SYSTEM_USER_ID.equals(provider.getUserId()),
                provider.getCreatedAt()
        );
    }
}
