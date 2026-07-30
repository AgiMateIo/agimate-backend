package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.LlmProviderCatalogEntry;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.enums.MediaTransportType;

import java.util.List;
import java.util.Map;

@Schema(description = "A known provider offered as a prefill for the create form. Values map "
        + "one-to-one onto CreateLlmProviderRequest; the user still supplies name and api_key")
public record LlmProviderCatalogResponse(
        @Schema(description = "Catalogue key (e.g. \"openrouter\")")
        String code,

        @Schema(description = "Brand name for the picker")
        String name,

        @Schema(description = "What this provider is, in the installation's content language")
        String description,

        @Schema(description = "Provider type to submit")
        LlmProviderType providerType,

        @Schema(description = "Base URL to submit")
        String baseUrl,

        @Schema(description = "Media dialect to submit; null — the default CHAT_MODALITIES")
        MediaTransportType mediaTransport,

        @Schema(description = "Models to start from, per purpose, in priority order. A suggestion: "
                + "run refresh-models afterwards and let the user adjust against the live registry")
        Map<LlmPurpose, List<String>> purposePriority,

        @Schema(description = "Where the user gets an API key; null when the page is not known")
        String apiKeyUrl
) {
    public static LlmProviderCatalogResponse from(LlmProviderCatalogEntry entry) {
        return new LlmProviderCatalogResponse(
                entry.getCode(),
                entry.getName(),
                entry.getDescription(),
                entry.getProviderType(),
                entry.getBaseUrl(),
                entry.getMediaTransport(),
                entry.getPurposePriority(),
                entry.getApiKeyUrl()
        );
    }
}
