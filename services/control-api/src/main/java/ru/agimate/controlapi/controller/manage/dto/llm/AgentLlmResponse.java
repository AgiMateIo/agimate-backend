package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.PlatformLlm;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;

import java.util.UUID;

@Schema(description = "Agent ↔ LLM binding (no api_key)")
public record AgentLlmResponse(
        @Schema(description = "Model name")
        String model,

        @Schema(description = "Binding role: CHAT — the agent-loop model, "
                + "IMAGE/VISION/AUDIO_IN/AUDIO_OUT — media model-as-tool bindings, ROUTINE — platform chores")
        LlmPurpose purpose,

        @Schema(description = "Position in the purpose's chain; the resolver takes the first eligible "
                + "binding")
        int priority,

        @Schema(description = "LLM provider public ID (null for a platform row)")
        UUID llmProviderId,

        @Schema(description = "LLM provider human-readable name")
        String llmProviderName,

        @Schema(description = "LLM provider type")
        LlmProviderType providerType,

        @Schema(description = "Where the binding comes from: USER — the agent's own agent_llms row, "
                + "PLATFORM — the platform's platform_llms row")
        Source source,

        @Schema(description = "Whether the binding is effective. A PLATFORM agent's own rows are kept "
                + "but inactive; the platform rows serve it")
        boolean active
) {
    public enum Source { USER, PLATFORM }

    public static AgentLlmResponse from(AgentLlm binding, LlmProvider provider, boolean active) {
        return new AgentLlmResponse(
                binding.getModel(),
                binding.getPurpose(),
                binding.getPriority(),
                binding.getLlmProviderId(),
                provider != null ? provider.getName() : null,
                provider != null ? provider.getProviderType() : null,
                Source.USER,
                active
        );
    }

    /** One effective platform row; its provider id is not addressable by the user. */
    public static AgentLlmResponse platform(PlatformLlm binding, LlmProvider provider) {
        return new AgentLlmResponse(
                binding.getModel(),
                binding.getPurpose(),
                binding.getPriority(),
                null,
                provider != null ? provider.getName() : null,
                provider != null ? provider.getProviderType() : null,
                Source.PLATFORM,
                true
        );
    }
}
