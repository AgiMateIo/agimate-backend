package ru.agimate.controlapi.service.dto.llm;

import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.enums.MediaTransportType;

import java.util.List;
import java.util.Map;

/**
 * The service-layer contract for creating an LLM provider — the input of the new
 * {@code LlmProviderService.create(UUID, LlmProviderCreateCommand)} overload, shared by the HTTP
 * boundary (mapping from {@code CreateLlmProviderRequest}) and the connector layer (the platform
 * connector), so the latter does not depend on {@code controller/**}.
 *
 * <p>{@code apiKey} is write-only: it is stored encrypted and never appears in any return record.
 * {@code purposePriority}: an empty list for a purpose switches it off; a missing key means the
 * purpose is not configured.
 */
public record LlmProviderCreateCommand(
        String name,
        LlmProviderType providerType,
        String baseUrl,
        String apiKey,
        Map<LlmPurpose, List<String>> purposePriority,
        Map<String, Object> extraBody,
        MediaTransportType mediaTransport,
        Boolean enabled
) {
}
