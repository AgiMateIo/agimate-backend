package ru.agimate.deviceapi.controller.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.LlmProviderType;

@Schema(description = "LLM credentials returned to the agent at runtime")
public record AgentLlmRuntimeResponse(
        @Schema(description = "Binding label (e.g. \"main_model\")")
        String name,

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
