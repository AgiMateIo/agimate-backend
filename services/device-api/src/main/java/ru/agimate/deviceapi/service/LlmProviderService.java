package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.connectors.integrations.IntegrationEncryptionService;
import ru.agimate.deviceapi.controller.manage.dto.llm.CreateLlmProviderRequest;
import ru.agimate.deviceapi.controller.manage.dto.llm.LlmProviderResponse;
import ru.agimate.deviceapi.controller.manage.dto.llm.RefreshModelsResponse;
import ru.agimate.deviceapi.controller.manage.dto.llm.UpdateLlmProviderRequest;
import ru.agimate.deviceapi.database.entities.LlmProvider;
import ru.agimate.deviceapi.database.entities.LlmProviderType;
import ru.agimate.deviceapi.database.repositories.LlmProviderRepository;
import ru.agimate.deviceapi.service.llm.LlmModelDiscoveryService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LlmProviderService {

    private static final String API_KEY_FIELD = "api_key";

    private final LlmProviderRepository llmProviderRepository;
    private final IntegrationEncryptionService encryptionService;
    private final LlmModelDiscoveryService modelDiscoveryService;

    public List<LlmProviderResponse> listForUser(UUID userPubId) {
        return llmProviderRepository.findAllByUserPubIdOrderByCreatedAtDesc(userPubId).stream()
                .map(LlmProviderResponse::from)
                .toList();
    }

    public LlmProviderResponse getForUser(UUID pubId, UUID userPubId) {
        return LlmProviderResponse.from(requireOwned(pubId, userPubId));
    }

    public LlmProvider requireOwned(UUID pubId, UUID userPubId) {
        LlmProvider provider = llmProviderRepository.findByPubIdAndUserPubId(pubId, userPubId)
                .orElseThrow(() -> new NotFoundStatusException("LLM provider not found"));
        if (!provider.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        return provider;
    }

    @Transactional
    public LlmProviderResponse create(UUID userPubId, CreateLlmProviderRequest request) {
        validateBaseUrl(request.providerType(), request.baseUrl());
        if (llmProviderRepository.existsByUserPubIdAndName(userPubId, request.name())) {
            throw new ConflictStatusException("LLM provider with this name already exists");
        }

        LlmProvider provider = LlmProvider.builder()
                .userPubId(userPubId)
                .name(request.name())
                .providerType(request.providerType())
                .baseUrl(blankToNull(request.baseUrl()))
                .encryptedApiKey(encryptionService.encryptCredentials(Map.of(API_KEY_FIELD, request.apiKey())))
                .apiKeyMask(buildMask(request.apiKey()))
                .enabled(request.enabled() == null || request.enabled())
                .build();
        provider = llmProviderRepository.save(provider);

        log.info("Created LLM provider pubId={}, user={}, type={}",
                provider.getPubId(), userPubId, provider.getProviderType());
        return LlmProviderResponse.from(provider);
    }

    @Transactional
    public LlmProviderResponse update(UUID pubId, UUID userPubId, UpdateLlmProviderRequest request) {
        LlmProvider provider = requireOwned(pubId, userPubId);

        if (request.name() != null && !request.name().equals(provider.getName())) {
            if (llmProviderRepository.existsByUserPubIdAndName(userPubId, request.name())) {
                throw new ConflictStatusException("LLM provider with this name already exists");
            }
            provider.setName(request.name());
        }
        if (request.baseUrl() != null) {
            String normalized = blankToNull(request.baseUrl());
            validateBaseUrl(provider.getProviderType(), normalized);
            provider.setBaseUrl(normalized);
        }
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            provider.setEncryptedApiKey(encryptionService.encryptCredentials(Map.of(API_KEY_FIELD, request.apiKey())));
            provider.setApiKeyMask(buildMask(request.apiKey()));
        }
        if (request.enabled() != null) {
            provider.setEnabled(request.enabled());
        }

        provider = llmProviderRepository.save(provider);
        log.info("Updated LLM provider pubId={}", pubId);
        return LlmProviderResponse.from(provider);
    }

    @Transactional
    public void delete(UUID pubId, UUID userPubId) {
        LlmProvider provider = requireOwned(pubId, userPubId);
        llmProviderRepository.delete(provider);
        log.info("Deleted LLM provider pubId={}", pubId);
    }

    @Transactional
    public RefreshModelsResponse refreshModels(UUID pubId, UUID userPubId) {
        LlmProvider provider = requireOwned(pubId, userPubId);
        String apiKey = decryptApiKey(provider);
        List<String> models = modelDiscoveryService.discover(provider, apiKey);

        provider.setAvailableModels(models);
        provider.setModelsRefreshedAt(LocalDateTime.now());
        provider = llmProviderRepository.save(provider);

        log.info("Refreshed {} models for LLM provider pubId={}", models.size(), pubId);
        return new RefreshModelsResponse(provider.getAvailableModels(), provider.getModelsRefreshedAt());
    }

    public String decryptApiKey(LlmProvider provider) {
        Map<String, String> decrypted = encryptionService.decryptCredentials(provider.getEncryptedApiKey());
        String key = decrypted.get(API_KEY_FIELD);
        if (key == null) {
            throw new BadRequestStatusException("Stored credentials are missing api_key field");
        }
        return key;
    }

    private void validateBaseUrl(LlmProviderType providerType, String baseUrl) {
        if (providerType == LlmProviderType.OPENAI_COMPATIBLE
                && (baseUrl == null || baseUrl.isBlank())) {
            throw new BadRequestStatusException("base_url is required for OPENAI_COMPATIBLE provider");
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String buildMask(String apiKey) {
        if (apiKey == null) {
            return "****";
        }
        if (apiKey.length() <= 4) {
            return "****";
        }
        String tail = apiKey.substring(apiKey.length() - 4);
        int prefixEnd = apiKey.indexOf('-');
        String prefix = prefixEnd > 0 && prefixEnd <= 6
                ? apiKey.substring(0, prefixEnd + 1)
                : "";
        return prefix + "..." + tail;
    }
}
