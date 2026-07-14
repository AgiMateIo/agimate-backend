package ru.agimate.controlapi.service.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.database.model.LlmModelInfo;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.enums.LlmProviderType;

import java.util.List;

@Slf4j
@Component
public class OpenAiCompatibleModelDiscovery implements LlmModelDiscoveryStrategy {

    @Override
    public LlmProviderType type() {
        return LlmProviderType.OPENAI_COMPATIBLE;
    }

    @Override
    public List<LlmModelInfo> listModels(LlmProvider provider, String decryptedApiKey) {
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
            throw new BadRequestStatusException("base_url is required for OPENAI_COMPATIBLE provider");
        }

        String baseUrl = stripTrailingSlash(provider.getBaseUrl());

        // OpenAI-compatible /models often returns just {id}; some servers (OpenRouter, LM Studio)
        // include a "name" field, so we opportunistically pick it up as the displayName.
        return LlmDiscoveryHttp.client(baseUrl).get()
                .uri("/models")
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + decryptedApiKey)
                .exchange((req, res) -> LlmDiscoveryHttp.extractModels(res, "data", "id", "name"));
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
