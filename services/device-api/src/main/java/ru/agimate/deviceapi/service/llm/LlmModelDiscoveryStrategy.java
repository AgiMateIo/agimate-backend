package ru.agimate.deviceapi.service.llm;

import ru.agimate.deviceapi.database.entities.LlmProvider;
import ru.agimate.deviceapi.database.enums.LlmProviderType;

import java.util.List;

public interface LlmModelDiscoveryStrategy {

    LlmProviderType type();

    List<String> listModels(LlmProvider provider, String decryptedApiKey);
}
