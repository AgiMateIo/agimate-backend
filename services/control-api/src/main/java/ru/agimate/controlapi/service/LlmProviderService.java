package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.controller.manage.dto.llm.CreateLlmProviderRequest;
import ru.agimate.controlapi.controller.manage.dto.llm.CreatePlatformLlmProviderRequest;
import ru.agimate.controlapi.controller.manage.dto.llm.LlmProviderModelResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.LlmProviderResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.RefreshModelsResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.UpdateLlmProviderRequest;
import ru.agimate.controlapi.controller.manage.dto.llm.UpsertModelExtraBodyRequest;
import ru.agimate.controlapi.database.model.LlmModelInfo;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.LlmProviderModel;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.enums.LlmProviderModelStatus;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.repositories.LlmProviderModelRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.database.repositories.SecretRepository;
import ru.agimate.controlapi.service.llm.LlmModelDiscoveryService;
import ru.agimate.controlapi.service.secret.SecretService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LlmProviderService {

    private static final String API_KEY_FIELD = "api_key";
    private static final String SECRET_ENTITY = "llm_provider";

    /** Кап на сериализованный extra_body (провайдер- и пер-модельный). */
    private static final int EXTRA_BODY_MAX_CHARS = 16 * 1024;

    /** Имя платформенного провайдера под {@link SystemSkillBootstrap#SYSTEM_USER_ID}. */
    public static final String PLATFORM_PROVIDER_NAME = "platform";

    private final LlmProviderRepository llmProviderRepository;
    private final LlmProviderModelRepository llmProviderModelRepository;
    private final SecretRepository secretRepository;
    private final SecretService secretService;
    private final LlmModelDiscoveryService modelDiscoveryService;

    /** Админ видит в списке и платформенную строку (управление free-tier через те же эндпойнты). */
    public List<LlmProviderResponse> listForUser(UUID userId, boolean admin) {
        List<LlmProviderResponse> result = new java.util.ArrayList<>(
                llmProviderRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                        .map(LlmProviderResponse::from)
                        .toList());
        if (admin) {
            llmProviderRepository
                    .findByUserIdAndName(SystemSkillBootstrap.SYSTEM_USER_ID, PLATFORM_PROVIDER_NAME)
                    .map(LlmProviderResponse::from)
                    .ifPresent(result::add);
        }
        return result;
    }

    /**
     * Платформенный провайдер, пригодный к fallback-выдаче: включён и с заданной default_model.
     * Пользовательские manage-пути сюда не смотрят — системная строка не принадлежит ни одному userId.
     */
    public Optional<LlmProvider> findUsablePlatformProvider() {
        return llmProviderRepository
                .findByUserIdAndName(SystemSkillBootstrap.SYSTEM_USER_ID, PLATFORM_PROVIDER_NAME)
                .filter(LlmProvider::isEnabled)
                .filter(p -> p.getDefaultModel() != null && !p.getDefaultModel().isBlank());
    }

    public LlmProviderResponse getForUser(UUID id, UUID userId, boolean admin) {
        return LlmProviderResponse.from(requireOwnedOrPlatformAdmin(id, userId, admin));
    }

    public LlmProvider requireOwned(UUID id, UUID userId) {
        LlmProvider provider = llmProviderRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundStatusException("LLM provider not found"));
        if (!provider.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        return provider;
    }

    /**
     * Своя строка — как {@link #requireOwned}; админу дополнительно доступна платформенная
     * (владелец — system-пользователь). Чужие пользовательские строки и для админа 404 —
     * он управляет free-tier, а не чужими ключами.
     */
    public LlmProvider requireOwnedOrPlatformAdmin(UUID id, UUID userId, boolean admin) {
        if (admin) {
            Optional<LlmProvider> provider = llmProviderRepository.findById(id)
                    .filter(p -> p.getUserId().equals(userId) || isPlatform(p));
            if (provider.isPresent()) {
                return provider.get();
            }
            throw new NotFoundStatusException("LLM provider not found");
        }
        return requireOwned(id, userId);
    }

    public static boolean isPlatform(LlmProvider provider) {
        return SystemSkillBootstrap.SYSTEM_USER_ID.equals(provider.getUserId());
    }

    /**
     * Создать платформенного провайдера из admin UI. Имя форсируется
     * {@link #PLATFORM_PROVIDER_NAME} (ключ fallback-выдачи), создаётся выключенным — включение после
     * настройки квот. Синглтон: повторное создание — 409.
     */
    @Transactional
    public LlmProviderResponse createPlatformProvider(CreatePlatformLlmProviderRequest request) {
        if (llmProviderRepository
                .findByUserIdAndName(SystemSkillBootstrap.SYSTEM_USER_ID, PLATFORM_PROVIDER_NAME)
                .isPresent()) {
            throw new ConflictStatusException("Platform provider already exists");
        }
        validateBaseUrl(request.providerType(), request.baseUrl());

        LlmProvider provider = llmProviderRepository.save(LlmProvider.builder()
                .userId(SystemSkillBootstrap.SYSTEM_USER_ID)
                .name(PLATFORM_PROVIDER_NAME)
                .providerType(request.providerType())
                .baseUrl(blankToNull(request.baseUrl()))
                .defaultModel(blankToNull(request.defaultModel()))
                .apiKeyMask(buildMask(request.apiKey()))
                .enabled(false)
                .build());
        Secret secret = secretService.store(SECRET_ENTITY, provider.getId(),
                Map.of(API_KEY_FIELD, request.apiKey()));
        provider.setSecretId(secret.getId());
        provider = llmProviderRepository.save(provider);

        log.info("Created platform LLM provider id={} type={} (enabled=false)",
                provider.getId(), provider.getProviderType());
        return LlmProviderResponse.from(provider);
    }

    @Transactional
    public LlmProviderResponse create(UUID userId, CreateLlmProviderRequest request) {
        validateBaseUrl(request.providerType(), request.baseUrl());
        validateExtraBody(request.extraBody());
        if (llmProviderRepository.existsByUserIdAndName(userId, request.name())) {
            throw new ConflictStatusException("LLM provider with this name already exists");
        }

        // id нужен до шифрования секрета (AAD-привязка) — сохраняем провайдера первым.
        LlmProvider provider = llmProviderRepository.save(LlmProvider.builder()
                .userId(userId)
                .name(request.name())
                .providerType(request.providerType())
                .baseUrl(blankToNull(request.baseUrl()))
                .defaultModel(blankToNull(request.defaultModel()))
                .extraBody(request.extraBody())
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
    public LlmProviderResponse update(UUID id, UUID userId, boolean admin, UpdateLlmProviderRequest request) {
        LlmProvider provider = requireOwnedOrPlatformAdmin(id, userId, admin);

        if (request.name() != null && !request.name().equals(provider.getName())) {
            if (isPlatform(provider)) {
                // Имя platform — ключ fallback-lookup'а; переименование сломало бы выдачу кредов.
                throw new BadRequestStatusException("Platform provider cannot be renamed");
            }
            if (llmProviderRepository.existsByUserIdAndName(userId, request.name())) {
                throw new ConflictStatusException("LLM provider with this name already exists");
            }
            provider.setName(request.name());
        }
        if (request.defaultModel() != null) {
            provider.setDefaultModel(blankToNull(request.defaultModel()));
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
        if (request.extraBody() != null) {
            validateExtraBody(request.extraBody());
            // Пустой объект — очистка (partial update: null = «не менять»).
            provider.setExtraBody(request.extraBody().isEmpty() ? null : request.extraBody());
        }
        if (request.enabled() != null) {
            provider.setEnabled(request.enabled());
        }

        provider = llmProviderRepository.save(provider);
        log.info("Updated LLM provider id={}", id);
        return LlmProviderResponse.from(provider);
    }

    @Transactional
    public void delete(UUID id, UUID userId, boolean admin) {
        LlmProvider provider = requireOwnedOrPlatformAdmin(id, userId, admin);
        if (isPlatform(provider)) {
            // Внезапная потеря fallback'а (+ каскад квот); выключение — через enabled.
            throw new BadRequestStatusException("Platform provider cannot be deleted; disable it instead");
        }
        UUID secretId = provider.getSecretId();
        llmProviderRepository.delete(provider);
        if (secretId != null) {
            secretRepository.deleteById(secretId);
        }
        log.info("Deleted LLM provider id={}", id);
    }

    /**
     * Синхронизация реестра {@code llm_provider_models} с листингом провайдера (upsert):
     * увиденные модели — обновить метаданные + {@code last_seen_at} + AVAILABLE, пропавшие —
     * UNAVAILABLE (строка не удаляется: на ней конфиг и биндинги). Guard: пустой листинг
     * статусы не трогает — один сбойный /models не должен массово «уронить» реестр
     * (сбойный HTTP-запрос кидает исключение ещё в discovery).
     */
    @Transactional
    public RefreshModelsResponse refreshModels(UUID id, UUID userId, boolean admin) {
        LlmProvider provider = requireOwnedOrPlatformAdmin(id, userId, admin);
        String apiKey = decryptApiKey(provider);
        List<LlmModelInfo> discovered = modelDiscoveryService.discover(provider, apiKey);

        if (discovered.isEmpty()) {
            log.warn("LLM provider {} returned an empty model listing — registry statuses left untouched", id);
        } else {
            upsertModels(provider, discovered);
            provider.setModelsRefreshedAt(LocalDateTime.now());
            provider = llmProviderRepository.save(provider);
            log.info("Refreshed {} models for LLM provider id={}", discovered.size(), id);
        }
        return new RefreshModelsResponse(listModels(provider.getId()), provider.getModelsRefreshedAt());
    }

    private void upsertModels(LlmProvider provider, List<LlmModelInfo> discovered) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, LlmProviderModel> existing = llmProviderModelRepository
                .findAllByProviderIdOrderByModel(provider.getId()).stream()
                .collect(Collectors.toMap(LlmProviderModel::getModel, Function.identity()));

        List<LlmProviderModel> toSave = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LlmModelInfo info : discovered) {
            if (!seen.add(info.id())) {
                continue; // дубль в листинге провайдера
            }
            LlmProviderModel row = existing.get(info.id());
            if (row == null) {
                row = LlmProviderModel.builder()
                        .providerId(provider.getId())
                        .model(info.id())
                        .firstSeenAt(now)
                        .build();
            }
            row.setDisplayName(info.displayName());
            row.setContextWindow(info.contextWindow());
            row.setInputModalities(info.inputModalities());
            row.setSupportedParameters(info.supportedParameters());
            row.setStatus(LlmProviderModelStatus.AVAILABLE);
            if (row.getFirstSeenAt() == null) {
                row.setFirstSeenAt(now); // конфиг был заведён руками до первого появления в листинге
            }
            row.setLastSeenAt(now);
            toSave.add(row);
        }
        for (LlmProviderModel row : existing.values()) {
            if (!seen.contains(row.getModel()) && row.getStatus() != LlmProviderModelStatus.UNAVAILABLE) {
                row.setStatus(LlmProviderModelStatus.UNAVAILABLE);
                toSave.add(row);
                log.info("Model '{}' of LLM provider {} disappeared from the listing — marked UNAVAILABLE",
                        row.getModel(), provider.getId());
            }
        }
        llmProviderModelRepository.saveAll(toSave);
    }

    /** Реестр моделей провайдера (сортировка по model). */
    public List<LlmProviderModelResponse> listModels(UUID providerId) {
        return llmProviderModelRepository.findAllByProviderIdOrderByModel(providerId).stream()
                .map(LlmProviderModelResponse::from)
                .toList();
    }

    public List<LlmProviderModelResponse> listModelsForUser(UUID id, UUID userId, boolean admin) {
        return listModels(requireOwnedOrPlatformAdmin(id, userId, admin).getId());
    }

    /**
     * Задать/очистить пер-модельный extra_body. Upsert: строка создаётся и для модели, которой
     * нет в листинге ({@code first_seen_at} null, UNAVAILABLE) — конфиг возможен до refresh.
     */
    @Transactional
    public LlmProviderModelResponse upsertModelExtraBody(
            UUID id, UUID userId, boolean admin, UpsertModelExtraBodyRequest request) {
        LlmProvider provider = requireOwnedOrPlatformAdmin(id, userId, admin);
        validateExtraBody(request.extraBody());
        LlmProviderModel row = llmProviderModelRepository
                .findByProviderIdAndModel(provider.getId(), request.model())
                .orElseGet(() -> LlmProviderModel.builder()
                        .providerId(provider.getId())
                        .model(request.model())
                        .status(LlmProviderModelStatus.UNAVAILABLE)
                        .build());
        row.setExtraBody(request.extraBody());
        row = llmProviderModelRepository.save(row);
        log.info("Set extra_body for model '{}' of LLM provider {} ({} keys)",
                request.model(), id, request.extraBody() == null ? 0 : request.extraBody().size());
        return LlmProviderModelResponse.from(row);
    }

    /**
     * Extra_body — не секрет-стор и не безразмерный: только компактный JSON-объект.
     * Содержимое не валидируем (allowlist ключей дрейфовал бы за провайдерами) — мусор
     * отвергнет сам провайдер.
     */
    private static void validateExtraBody(Map<String, Object> extraBody) {
        if (extraBody == null) {
            return;
        }
        int length = JsonUtils.toJson(extraBody)
                .orElseThrow(() -> new BadRequestStatusException("extra_body is not serializable to JSON"))
                .length();
        if (length > EXTRA_BODY_MAX_CHARS) {
            throw new BadRequestStatusException(
                    "extra_body is too large (" + length + " chars, max " + EXTRA_BODY_MAX_CHARS + ")");
        }
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
