package ru.agimate.controlapi.service.llm.discovery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.model.LlmModelInfo;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.enums.LlmProviderType;

import java.util.List;

@Slf4j
@Component
public class GeminiModelDiscovery implements LlmModelDiscoveryStrategy {

    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    @Override
    public LlmProviderType type() {
        return LlmProviderType.GEMINI;
    }

    @Override
    public List<LlmModelInfo> listModels(LlmProvider provider, String decryptedApiKey) {
        String baseUrl = provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank()
                ? provider.getBaseUrl()
                : DEFAULT_BASE_URL;

        // Gemini /v1beta/models entries: {name, displayName, description, version, inputTokenLimit, ...}.
        // We use `name` (e.g. "models/gemini-1.5-pro") as the id and `displayName` as the label.
        return LlmDiscoveryHttp.client(baseUrl).get()
                .uri(uriBuilder -> uriBuilder.path("/v1beta/models")
                        .queryParam("key", decryptedApiKey)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .exchange((req, res) -> LlmDiscoveryHttp.extractModels(res, "models", "name", "displayName"));
    }
}
