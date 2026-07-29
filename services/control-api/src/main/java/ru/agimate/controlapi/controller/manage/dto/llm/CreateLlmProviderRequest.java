package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.enums.MediaTransportType;

import java.util.List;
import java.util.Map;

@Schema(description = "Request to create an LLM provider configuration")
public record CreateLlmProviderRequest(
        @NotBlank
        @Schema(description = "Human-readable name (unique per user), e.g. \"Production OpenAI\"")
        String name,

        @NotNull
        @Schema(description = "Provider type")
        LlmProviderType providerType,

        @Schema(description = "Custom base URL (required for OPENAI_COMPATIBLE, optional for others)")
        String baseUrl,

        @NotBlank
        @Schema(description = "API key — encrypted at rest, never returned in responses")
        String apiKey,

        @Schema(description = "Models allowed per purpose, in priority order "
                + "(e.g. {\"CHAT\": [\"m1\", \"m2\"], \"VISION\": [\"m3\"]}). An allowlist: a purpose "
                + "with no key is reported as unconfigured, an empty list switches the purpose off")
        Map<LlmPurpose, List<String>> purposePriority,

        @Schema(description = "Provider-level extra chat/completions body fields (e.g. OpenRouter "
                + "provider routing); deep-merged with per-model extra_body, model wins. "
                + "Not a secret store — do not put API keys here")
        Map<String, Object> extraBody,

        @Schema(description = "How this provider is asked to generate an image; absent — "
                + "CHAT_MODALITIES (the OpenRouter convention). Providers whose image models live on "
                + "a separate endpoint (Polza) need MEDIA_ENDPOINT")
        MediaTransportType mediaTransport,

        @Schema(description = "Whether the provider is enabled")
        Boolean enabled
) {
}
