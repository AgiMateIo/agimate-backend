package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;

import java.util.List;
import java.util.Map;

/**
 * Creation of the platform (free-tier) provider. The name is not accepted — it is forced to
 * {@code "platform"} (the key of the fallback issue), and {@code enabled} is ignored (the row is
 * created disabled). Hence a separate DTO without {@code name}/{@code enabled} rather than the shared
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

        @Schema(description = "Models the free tier is allowed to spend on, per purpose, in priority "
                + "order (e.g. {\"CHAT\": [\"m1\", \"m2\"], \"IMAGE\": [\"m3\"]}). CHAT is what agents "
                + "without a binding of their own fall back to; a purpose left out is reported as "
                + "unconfigured rather than picked automatically")
        Map<LlmPurpose, List<String>> purposePriority
) {
}
