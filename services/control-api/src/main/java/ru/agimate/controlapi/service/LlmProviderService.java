package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.manage.dto.llm.CreateLlmProviderRequest;
import ru.agimate.controlapi.controller.manage.dto.llm.LlmProviderResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.RefreshModelsResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.UpdateLlmProviderRequest;
import ru.agimate.controlapi.database.entities.LlmModelInfo;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.database.repositories.SecretRepository;
import ru.agimate.controlapi.service.llm.LlmModelDiscoveryService;
import ru.agimate.controlapi.service.secret.SecretService;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LlmProviderService {

    private static final String API_KEY_FIELD = "api_key";
    private static final String SECRET_ENTITY = "llm_provider";

    private final LlmProviderRepository llmProviderRepository;
    private final SecretRepository secretRepository;
    private final SecretService secretService;
    private final LlmModelDiscoveryService modelDiscoveryService;

    public List<LlmProviderResponse> listForUser(UUID userId) {
        return llmProviderRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(LlmProviderResponse::from)
                .toList();
    }

    public LlmProviderResponse getForUser(UUID id, UUID userId) {
        return LlmProviderResponse.from(requireOwned(id, userId));
    }

    public LlmProvider requireOwned(UUID id, UUID userId) {
        LlmProvider provider = llmProviderRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundStatusException("LLM provider not found"));
        if (!provider.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        return provider;
    }

    @Transactional
    public LlmProviderResponse create(UUID userId, CreateLlmProviderRequest request) {
        validateBaseUrl(request.providerType(), request.baseUrl());
        if (llmProviderRepository.existsByUserIdAndName(userId, request.name())) {
            throw new ConflictStatusException("LLM provider with this name already exists");
        }

        // id нужен до шифрования секрета (AAD-привязка) — сохраняем провайдера первым.
        LlmProvider provider = llmProviderRepository.save(LlmProvider.builder()
                .userId(userId)
                .name(request.name())
                .providerType(request.providerType())
                .baseUrl(blankToNull(request.baseUrl()))
                .apiKeyMask(buildMask(request.apiKey()))
                .enabled(request.enabled() == null || request.enabled())
                .build());

        Secret secret = secretService.store(SECRET_ENTITY, provider.getId(),
                Map.of(API_KEY_FIELD, request.apiKey()));
        provider.setSecretId(secret.getId());
        provider = llmProviderRepository.save(provider);

        log.info("Created LLM provider id={}, user={}, type={}",
                provider.getId(), userId, provider.getProviderType());
        return LlmProviderResponse.from(provider);
    }

    @Transactional
    public LlmProviderResponse update(UUID id, UUID userId, UpdateLlmProviderRequest request) {
        LlmProvider provider = requireOwned(id, userId);

        if (request.name() != null && !request.name().equals(provider.getName())) {
            if (llmProviderRepository.existsByUserIdAndName(userId, request.name())) {
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
            Secret secret = secretRepository.findById(provider.getSecretId())
                    .orElseThrow(() -> new NotFoundStatusException("Secret not found for LLM provider " + id));
            secretService.update(secret, provider.getId(), Map.of(API_KEY_FIELD, request.apiKey()));
            provider.setApiKeyMask(buildMask(request.apiKey()));
        }
        if (request.enabled() != null) {
            provider.setEnabled(request.enabled());
        }

        provider = llmProviderRepository.save(provider);
        log.info("Updated LLM provider id={}", id);
        return LlmProviderResponse.from(provider);
    }

    @Transactional
    public void delete(UUID id, UUID userId) {
        LlmProvider provider = requireOwned(id, userId);
        UUID secretId = provider.getSecretId();
        llmProviderRepository.delete(provider);
        if (secretId != null) {
            secretRepository.deleteById(secretId);
        }
        log.info("Deleted LLM provider id={}", id);
    }

    @Transactional
    public RefreshModelsResponse refreshModels(UUID id, UUID userId) {
        LlmProvider provider = requireOwned(id, userId);
        String apiKey = decryptApiKey(provider);
        // Sort by id (always present), falling back to nothing else — displayName is optional.
        List<LlmModelInfo> models = modelDiscoveryService.discover(provider, apiKey).stream()
                .sorted(Comparator.comparing(LlmModelInfo::id, Comparator.nullsLast(String::compareTo)))
                .toList();

        provider.setAvailableModels(models);
        provider.setModelsRefreshedAt(LocalDateTime.now());
        provider = llmProviderRepository.save(provider);

        log.info("Refreshed {} models for LLM provider id={}", models.size(), id);
        return new RefreshModelsResponse(provider.getAvailableModels(), provider.getModelsRefreshedAt());
    }

    public String decryptApiKey(LlmProvider provider) {
        Secret secret = secretRepository.findById(provider.getSecretId())
                .orElseThrow(() -> new BadRequestStatusException("LLM provider has no stored API key"));
        String key = secretService.reveal(secret, provider.getId()).get(API_KEY_FIELD);
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
        int prefixEnd = apiKey.indexOf('-');
        String prefix = prefixEnd > 0 && prefixEnd <= 6
                ? apiKey.substring(0, prefixEnd + 1)
                : "";
        int remaining = apiKey.length() - prefix.length();
        if (remaining <= 8) {
            return prefix + "****";
        }
        String head = apiKey.substring(prefix.length(), prefix.length() + 4);
        String tail = apiKey.substring(apiKey.length() - 4);
        return prefix + head + "..." + tail;
    }
}
