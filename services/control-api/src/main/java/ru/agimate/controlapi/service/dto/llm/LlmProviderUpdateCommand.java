package ru.agimate.controlapi.service.dto.llm;

import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.enums.MediaTransportType;

import java.util.List;
import java.util.Map;

/**
 * The service-layer contract for a partial update of an LLM provider — the input of the new
 * {@code LlmProviderService.update(UUID, UUID, LlmProviderUpdateCommand)} overload, shared by the
 * HTTP boundary (mapping from {@code UpdateLlmProviderRequest}) and the connector layer (the platform
 * connector), so the latter does not depend on {@code controller/**}.
 *
 * <p>PATCH semantics, implemented by the service: {@code null} = keep the current value, an empty
 * {@code purposePriority}/{@code extraBody} map = clear, a non-blank {@code apiKey} = replace (the
 * key itself is never returned). The connector passes raw (possibly null) values straight through.
 */
public record LlmProviderUpdateCommand(
        String name,
        String baseUrl,
        String apiKey,
        Map<LlmPurpose, List<String>> purposePriority,
        Map<String, Object> extraBody,
        MediaTransportType mediaTransport,
        Boolean enabled
) {
}
