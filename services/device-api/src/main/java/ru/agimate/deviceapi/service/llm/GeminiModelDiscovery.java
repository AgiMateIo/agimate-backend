package ru.agimate.deviceapi.service.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import ru.agimate.deviceapi.database.entities.LlmProvider;
import ru.agimate.deviceapi.database.entities.LlmProviderType;

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
    public List<String> listModels(LlmProvider provider, String decryptedApiKey) {
        String baseUrl = provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank()
                ? provider.getBaseUrl()
                : DEFAULT_BASE_URL;

        return LlmDiscoveryHttp.client(baseUrl).get()
                .uri(uriBuilder -> uriBuilder.path("/v1beta/models")
                        .queryParam("key", decryptedApiKey)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .exchange((req, res) -> LlmDiscoveryHttp.extractIds(res, "models", "name"));
    }
}
