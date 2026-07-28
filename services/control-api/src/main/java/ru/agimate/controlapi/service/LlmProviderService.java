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
import ru.agimate.controlapi.database.entities.LlmModelDefaults;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.LlmProviderModel;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.enums.LlmProviderModelStatus;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.repositories.LlmModelDefaultsRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderModelRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.database.repositories.SecretRepository;
import ru.agimate.controlapi.service.llm.LlmModelDiscoveryService;
import ru.agimate.controlapi.service.secret.SecretService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
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

    /** Cap on the serialised extra_body (both provider-level and per-model). */
    private static final int EXTRA_BODY_MAX_CHARS = 16 * 1024;

    /** Name of the platform provider under {@link SystemSkillBootstrap#SYSTEM_USER_ID}. */
    public static final String PLATFORM_PROVIDER_NAME = "platform";

    private final LlmProviderRepository llmProviderRepository;
    private final LlmProviderModelRepository llmProviderModelRepository;
    private final LlmModelDefaultsRepository llmModelDefaultsRepository;
    private final SecretRepository secretRepository;
    private final SecretService secretService;
    private final LlmModelDiscoveryService modelDiscoveryService;

    /** An admin also sees the platform row in the list (the free tier is managed through the same endpoints). */
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
     * The platform provider fit for a fallback issue: just «enabled». Whether it can serve a given
     * purpose is decided by the caller against {@code purpose_priority} — that way the refusal names
     * the purpose that is unconfigured instead of the row silently disappearing from the fallback.
     * The users' manage paths never look here — the system row belongs to no userId.
     */
    public Optional<LlmProvider> findUsablePlatformProvider() {
        return llmProviderRepository
                .findByUserIdAndName(SystemSkillBootstrap.SYSTEM_USER_ID, PLATFORM_PROVIDER_NAME)
                .filter(LlmProvider::isEnabled);
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
     * One's own row — as in {@link #requireOwned}; an admin additionally gets the platform one (owned
     * by the system user). Other users' rows are a 404 even for an admin — they manage the free tier,
     * not other people's keys.
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
     * Create a platform provider from the admin UI. The name is forced to
     * {@link #PLATFORM_PROVIDER_NAME} (the key of the fallback issue), and it is created disabled —
     * enabling comes after the quotas are configured. A singleton: creating it again is a 409.
     */
    @Transactional
    public LlmProviderResponse createPlatformProvider(CreatePlatformLlmProviderRequest request) {
        if (llmProviderRepository
                .findByUserIdAndName(SystemSkillBootstrap.SYSTEM_USER_ID, PLATFORM_PROVIDER_NAME)
                .isPresent()) {
            throw new ConflictStatusException("Platform provider already exists");
        }
        validateBaseUrl(request.providerType(), request.baseUrl());
        validatePurposePriority(request.purposePriority());

        LlmProvider provider = llmProviderRepository.save(LlmProvider.builder()
                .userId(SystemSkillBootstrap.SYSTEM_USER_ID)
                .name(PLATFORM_PROVIDER_NAME)
                .providerType(request.providerType())
                .baseUrl(blankToNull(request.baseUrl()))
                .purposePriority(emptyToNull(request.purposePriority()))
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
        validatePurposePriority(request.purposePriority());
        if (llmProviderRepository.existsByUserIdAndName(userId, request.name())) {
            throw new ConflictStatusException("LLM provider with this name already exists");
        }

        // The id is needed before the secret is encrypted (the AAD binding) — so the provider is saved first.
        LlmProvider provider = llmProviderRepository.save(LlmProvider.builder()
                .userId(userId)
                .name(request.name())
                .providerType(request.providerType())
                .baseUrl(blankToNull(request.baseUrl()))
                .purposePriority(emptyToNull(request.purposePriority()))
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
                // The name «platform» is the key of the fallback lookup; renaming would break credential issuing.
                throw new BadRequestStatusException("Platform provider cannot be renamed");
            }
            if (llmProviderRepository.existsByUserIdAndName(userId, request.name())) {
                throw new ConflictStatusException("LLM provider with this name already exists");
            }
            provider.setName(request.name());
        }
        if (request.purposePriority() != null) {
            validatePurposePriority(request.purposePriority());
            validateModelsKnown(provider, request.purposePriority().values().stream()
                    .flatMap(List::stream).toList());
            // The whole map is replaced; an empty object clears it (partial update: null = «leave unchanged»).
            provider.setPurposePriority(emptyToNull(request.purposePriority()));
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
            // An empty object clears it (partial update: null = «leave unchanged»).
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
            // A sudden loss of the fallback (plus a cascade of quotas); to switch it off, use enabled.
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
     * Synchronisation of the {@code llm_provider_models} registry with the provider's listing (an
     * upsert): models seen get their metadata plus {@code last_seen_at} plus AVAILABLE, models gone
     * become UNAVAILABLE (the row is not deleted: it holds config and bindings). Guard: an empty
     * listing leaves the statuses alone — one failing /models must not «take the registry down»
     * wholesale (a failing HTTP request throws back in discovery anyway).
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
        Map<String, LlmModelDefaults> defaults = llmModelDefaultsRepository
                .findByModelIn(discovered.stream().map(LlmModelInfo::id).toList()).stream()
                .collect(Collectors.toMap(LlmModelDefaults::getModel, Function.identity()));

        List<LlmProviderModel> toSave = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LlmModelInfo info : discovered) {
            if (!seen.add(info.id())) {
                continue; // a duplicate in the provider's listing
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
            row.setMaxOutputTokens(info.maxOutputTokens());
            row.setInputModalities(info.inputModalities());
            row.setOutputModalities(info.outputModalities());
            row.setSupportedParameters(info.supportedParameters());
            row.setRawMetadata(info.rawMetadata());
            applyDefaults(row, defaults.get(info.id())); // a write-time overlay: the gaps left by discovery
            row.setStatus(LlmProviderModelStatus.AVAILABLE);
            if (row.getFirstSeenAt() == null) {
                row.setFirstSeenAt(now); // the config was entered by hand before the model first appeared in a listing
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

    /**
     * Per-field fallback from {@code llm_model_defaults}: we fill in only the capabilities discovery
     * did not report (discovered wins). Write-time — an edit to the reference table reaches the rows on
     * the next refresh. Provenance (discovered vs assumed) is deliberately not stored (see the design).
     */
    private static void applyDefaults(LlmProviderModel row, LlmModelDefaults def) {
        if (def == null) {
            return;
        }
        if (row.getDisplayName() == null) {
            row.setDisplayName(def.getDisplayName());
        }
        if (row.getContextWindow() == null) {
            row.setContextWindow(def.getContextWindow());
        }
        if (row.getMaxOutputTokens() == null) {
            row.setMaxOutputTokens(def.getMaxOutputTokens());
        }
        if (row.getInputModalities() == null) {
            row.setInputModalities(def.getInputModalities());
        }
        if (row.getOutputModalities() == null) {
            row.setOutputModalities(def.getOutputModalities());
        }
        if (row.getSupportedParameters() == null) {
            row.setSupportedParameters(def.getSupportedParameters());
        }
    }

    /** The provider's model registry (sorted by model). */
    public List<LlmProviderModelResponse> listModels(UUID providerId) {
        return llmProviderModelRepository.findAllByProviderIdOrderByModel(providerId).stream()
                .map(LlmProviderModelResponse::from)
                .toList();
    }

    public List<LlmProviderModelResponse> listModelsForUser(UUID id, UUID userId, boolean admin) {
        return listModels(requireOwnedOrPlatformAdmin(id, userId, admin).getId());
    }

    /**
     * Set or clear the per-model extra_body. An upsert: the row is created even for a model absent from
     * the listing ({@code first_seen_at} null, UNAVAILABLE) — config is possible before a refresh.
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
     * Shape of the purpose lists. A blank or repeated model id is a typo rather than a configuration,
     * and a null list is ambiguous where {@code []} already means «this purpose is switched off» — so
     * all three are refused at the boundary instead of surfacing as a confusing resolution failure.
     */
    private static void validatePurposePriority(Map<LlmPurpose, List<String>> purposePriority) {
        if (purposePriority == null) {
            return;
        }
        purposePriority.forEach((purpose, models) -> {
            if (models == null) {
                throw new BadRequestStatusException("purpose_priority." + purpose
                        + " is null; use [] to switch the purpose off");
            }
            Set<String> seen = new HashSet<>();
            for (String model : models) {
                if (model == null || model.isBlank()) {
                    throw new BadRequestStatusException(
                            "purpose_priority." + purpose + " contains a blank model id");
                }
                if (!seen.add(model)) {
                    throw new BadRequestStatusException(
                            "purpose_priority." + purpose + " lists '" + model + "' twice");
                }
            }
        });
    }

    /**
     * Protection against typos, using the {@code llm_provider_models} registry — for purpose lists as
     * well as for agent bindings, since neither is ever corrected by a capability search at call time.
     * The advisory principle: a row of any status passes (UNAVAILABLE = it disappeared from the last
     * listing, but naming it again is allowed — listings are sometimes incomplete); an empty registry
     * means discovery has never run, so we let everything through.
     */
    public void validateModelsKnown(LlmProvider provider, Collection<String> models) {
        if (models.isEmpty()) {
            return;
        }
        List<LlmProviderModel> registry = llmProviderModelRepository
                .findAllByProviderIdOrderByModel(provider.getId());
        if (registry.isEmpty()) {
            log.warn("LLM provider {} has an empty model registry — skipping model validation for {}",
                    provider.getId(), models);
            return;
        }
        List<String> known = registry.stream().map(LlmProviderModel::getModel).toList();
        for (String model : models) {
            if (!known.contains(model)) {
                throw new BadRequestStatusException(
                        "Model '" + model + "' is not in the provider's model registry. "
                                + "Refresh models or use one of: " + known);
            }
        }
    }

    /**
     * Extra_body is neither a secret store nor unbounded: a compact JSON object only. Its contents are
     * not validated (an allowlist of keys would drift behind the providers) — the provider itself
     * rejects garbage.
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

    private static Map<LlmPurpose, List<String>> emptyToNull(Map<LlmPurpose, List<String>> map) {
        return (map == null || map.isEmpty()) ? null : map;
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
