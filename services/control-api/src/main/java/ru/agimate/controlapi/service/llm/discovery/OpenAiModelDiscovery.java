package ru.agimate.controlapi.service.llm.discovery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.model.LlmModelInfo;
import ru.agimate.controlapi.service.http.PublicOnlyHttp;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.enums.LlmProviderType;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiModelDiscovery implements LlmModelDiscoveryStrategy {

    private final PublicOnlyHttp http;

    private static final String DEFAULT_BASE_URL = "https://api.openai.com";

    @Override
    public LlmProviderType type() {
        return LlmProviderType.OPENAI;
    }

    @Override
    public List<LlmModelInfo> listModels(LlmProvider provider, String decryptedApiKey) {
        String baseUrl = provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank()
                ? provider.getBaseUrl()
                : DEFAULT_BASE_URL;

        // OpenAI /v1/models returns {id, object, created, owned_by} — no human-readable name.
        return LlmDiscoveryHttp.client(http, baseUrl).get()
                .uri("/v1/models")
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + decryptedApiKey)
                .exchange((req, res) -> LlmDiscoveryHttp.extractModels(res, "data", "id", null));
    }
}
