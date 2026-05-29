package ru.agimate.deviceapi.service.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import ru.agimate.deviceapi.database.entities.LlmProvider;
import ru.agimate.deviceapi.database.enums.LlmProviderType;

import java.util.List;

@Slf4j
@Component
public class AnthropicModelDiscovery implements LlmModelDiscoveryStrategy {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Override
    public LlmProviderType type() {
        return LlmProviderType.ANTHROPIC;
    }

    @Override
    public List<String> listModels(LlmProvider provider, String decryptedApiKey) {
        String baseUrl = provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank()
                ? provider.getBaseUrl()
                : DEFAULT_BASE_URL;

        return LlmDiscoveryHttp.client(baseUrl).get()
                .uri("/v1/models")
                .accept(MediaType.APPLICATION_JSON)
                .header("x-api-key", decryptedApiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .exchange((req, res) -> LlmDiscoveryHttp.extractIds(res, "data", "id"));
    }
}
