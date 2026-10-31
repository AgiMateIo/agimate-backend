package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.PlatformLlm;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;

import java.util.UUID;

@Schema(description = "One link of a platform purpose's chain; self-contained for the admin page")
public record PlatformLlmResponse(
        @Schema(description = "LLM provider public ID")
        UUID llmProviderId,

        @Schema(description = "LLM provider human-readable name")
        String providerName,

        @Schema(description = "LLM provider type")
        LlmProviderType providerType,

        @Schema(description = "Purpose served by the chain")
        LlmPurpose purpose,

        @Schema(description = "Position in the purpose's chain; the resolver tries the lower ones first")
        int priority,

        @Schema(description = "Model name")
        String model
) {
    public static PlatformLlmResponse from(PlatformLlm binding, LlmProvider provider) {
        return new PlatformLlmResponse(
                binding.getLlmProviderId(),
                provider != null ? provider.getName() : null,
                provider != null ? provider.getProviderType() : null,
                binding.getPurpose(),
                binding.getPriority(),
                binding.getModel()
        );
    }
}
