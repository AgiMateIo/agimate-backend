package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;

import java.util.UUID;

@Schema(description = "Agent ↔ LLM binding (no api_key)")
public record AgentLlmResponse(
        @Schema(description = "Binding label")
        String name,

        @Schema(description = "Model name")
        String model,

        @Schema(description = "Binding role: CHAT — agent-loop model, IMAGE/VISION/AUDIO_IN/AUDIO_OUT — "
                + "media model-as-tool bindings")
        LlmPurpose purpose,

        @Schema(description = "LLM provider public ID (null for the platform fallback)")
        UUID llmProviderId,

        @Schema(description = "LLM provider human-readable name")
        String llmProviderName,

        @Schema(description = "LLM provider type")
        LlmProviderType providerType,

        @Schema(description = "Where the binding comes from: USER — explicit agent_llms row, "
                + "PLATFORM — implicit fallback to the platform provider")
        Source source
) {
    public enum Source { USER, PLATFORM }

    public static AgentLlmResponse from(AgentLlm binding, LlmProvider provider) {
        return new AgentLlmResponse(
                binding.getName(),
                binding.getModel(),
                binding.getPurpose(),
                binding.getLlmProviderId(),
                provider != null ? provider.getName() : null,
                provider != null ? provider.getProviderType() : null,
                Source.USER
        );
    }

    /** Эффективная модель агента без привязок: платформенный fallback (id не адресуем юзером). */
    public static AgentLlmResponse platformFallback(LlmProvider platformProvider) {
        return new AgentLlmResponse(
                platformProvider.getName(),
                platformProvider.getDefaultModel(),
                LlmPurpose.CHAT,
                null,
                platformProvider.getName(),
                platformProvider.getProviderType(),
                Source.PLATFORM
        );
    }
}
