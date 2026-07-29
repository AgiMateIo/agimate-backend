package ru.agimate.controlapi.service.llm.discovery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.database.model.LlmModelInfo;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.enums.LlmProviderType;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LlmModelDiscoveryService {

    private final Map<LlmProviderType, LlmModelDiscoveryStrategy> strategies;

    public LlmModelDiscoveryService(List<LlmModelDiscoveryStrategy> strategies) {
        this.strategies = strategies.stream()
                .collect(Collectors.toUnmodifiableMap(LlmModelDiscoveryStrategy::type, Function.identity()));
    }

    public List<LlmModelInfo> discover(LlmProvider provider, String decryptedApiKey) {
        LlmModelDiscoveryStrategy strategy = strategies.get(provider.getProviderType());
        if (strategy == null) {
            throw new BadRequestStatusException(
                    "No model discovery strategy registered for provider type " + provider.getProviderType());
        }
        return strategy.listModels(provider, decryptedApiKey);
    }
}
