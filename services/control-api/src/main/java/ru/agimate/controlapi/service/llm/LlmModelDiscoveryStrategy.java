package ru.agimate.controlapi.service.llm;

import ru.agimate.controlapi.database.entities.LlmModelInfo;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.enums.LlmProviderType;

import java.util.List;

public interface LlmModelDiscoveryStrategy {

    LlmProviderType type();

    List<LlmModelInfo> listModels(LlmProvider provider, String decryptedApiKey);
}
