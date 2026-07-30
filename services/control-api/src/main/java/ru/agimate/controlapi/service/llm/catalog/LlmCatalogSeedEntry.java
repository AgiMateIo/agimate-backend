package ru.agimate.controlapi.service.llm.catalog;

import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.enums.MediaTransportType;

import java.util.List;
import java.util.Map;

/**
 * One entry of {@code seed/llm-providers.yaml} as read from disk.
 *
 * @param description English source text; the installation's language may replace it
 *                    ({@code LlmCatalogTexts})
 * @param mediaTransport null — the provider speaks the default {@code CHAT_MODALITIES}
 * @param apiKeyUrl null when the provider's key page is not known
 * @param purposePriority may leave a purpose out entirely — that is «we have no confirmed model id
 *                        for it», which reaches the user as «not configured» rather than as a guess
 */
public record LlmCatalogSeedEntry(
        String code,
        String name,
        String description,
        LlmProviderType providerType,
        String baseUrl,
        MediaTransportType mediaTransport,
        String apiKeyUrl,
        int sortOrder,
        Map<LlmPurpose, List<String>> purposePriority
) {
}
