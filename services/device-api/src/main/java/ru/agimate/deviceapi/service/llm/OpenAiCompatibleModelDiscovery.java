package ru.agimate.deviceapi.service.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.deviceapi.database.entities.LlmProvider;
import ru.agimate.deviceapi.database.entities.LlmProviderType;

import java.util.List;

@Slf4j
@Component
public class OpenAiCompatibleModelDiscovery implements LlmModelDiscoveryStrategy {

    @Override
    public LlmProviderType type() {
        return LlmProviderType.OPENAI_COMPATIBLE;
    }

    @Override
    public List<String> listModels(LlmProvider provider, String decryptedApiKey) {
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
            throw new BadRequestStatusException("base_url is required for OPENAI_COMPATIBLE provider");
        }

        return LlmDiscoveryHttp.client(provider.getBaseUrl()).get()
                .uri("/v1/models")
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + decryptedApiKey)
                .exchange((req, res) -> LlmDiscoveryHttp.extractIds(res, "data", "id"));
    }
}
