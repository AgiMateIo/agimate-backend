package ru.agimate.controlapi.service.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.enums.LlmProviderType;

import java.util.List;

@Slf4j
@Component
public class OpenAiModelDiscovery implements LlmModelDiscoveryStrategy {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com";

    @Override
    public LlmProviderType type() {
        return LlmProviderType.OPENAI;
    }

    @Override
    public List<String> listModels(LlmProvider provider, String decryptedApiKey) {
        String baseUrl = provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank()
                ? provider.getBaseUrl()
                : DEFAULT_BASE_URL;

        return LlmDiscoveryHttp.client(baseUrl).get()
                .uri("/v1/models")
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + decryptedApiKey)
                .exchange((req, res) -> LlmDiscoveryHttp.extractIds(res, "data", "id"));
    }
}
