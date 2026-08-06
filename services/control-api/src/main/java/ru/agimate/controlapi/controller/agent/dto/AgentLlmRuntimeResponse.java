package ru.agimate.controlapi.controller.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;

/**
 * The model an agent is configured to run on — metadata, never a credential. An external brain holds
 * its own copy of the provider's key: the one place that decrypts keys is
 * {@code LlmCredentialsResolver}, and it serves our own worker over gRPC, where the quota is checked
 * and the spend is reported back. A key handed over HTTP would outlive every one of those checks.
 */
@Schema(description = "LLM binding of the agent at runtime — which model to use, without credentials")
public record AgentLlmRuntimeResponse(
        @Schema(description = "Binding role (CHAT — the agent-loop model)")
        LlmPurpose purpose,

        @Schema(description = "Provider type")
        LlmProviderType providerType,

        @Schema(description = "Custom base URL or null for the provider's default")
        String baseUrl,

        @Schema(description = "Model to use")
        String model
) {
}
