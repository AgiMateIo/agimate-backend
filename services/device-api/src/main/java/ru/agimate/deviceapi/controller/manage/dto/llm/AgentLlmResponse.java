package ru.agimate.deviceapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.AgentLlm;
import ru.agimate.deviceapi.database.entities.LlmProvider;
import ru.agimate.deviceapi.database.enums.LlmProviderType;

import java.util.UUID;

@Schema(description = "Agent ↔ LLM binding (no api_key)")
public record AgentLlmResponse(
        @Schema(description = "Binding label")
        String name,

        @Schema(description = "Model name")
        String model,

        @Schema(description = "LLM provider public ID")
        UUID llmProviderPubId,

        @Schema(description = "LLM provider human-readable name")
        String llmProviderName,

        @Schema(description = "LLM provider type")
        LlmProviderType providerType
) {
    public static AgentLlmResponse from(AgentLlm binding, LlmProvider provider) {
        return new AgentLlmResponse(
                binding.getName(),
                binding.getModel(),
                binding.getLlmProviderPubId(),
                provider != null ? provider.getName() : null,
                provider != null ? provider.getProviderType() : null
        );
    }
}
