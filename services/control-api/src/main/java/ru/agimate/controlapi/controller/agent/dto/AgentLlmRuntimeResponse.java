package ru.agimate.controlapi.controller.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;

@Schema(description = "LLM credentials returned to the agent at runtime")
public record AgentLlmRuntimeResponse(
        @Schema(description = "Binding role (CHAT — the agent-loop model)")
        LlmPurpose purpose,

        @Schema(description = "Provider type")
        LlmProviderType providerType,

        @Schema(description = "Custom base URL or null for the provider's default")
        String baseUrl,

        @Schema(description = "Model to use")
        String model,

        @Schema(description = "Decrypted API key — keep in memory only, never log")
        String apiKey
) {
}
