package ru.agimate.controlapi.service.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.LlmProviderModel;
import ru.agimate.controlapi.database.enums.LlmProviderModelStatus;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.repositories.AgentLlmRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderModelRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.service.LlmProviderService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Единый конвейер выдачи LLM-кредов: выбор провайдера/модели → {@code enabled}-проверка →
 * квота → расшифровка ключа → deep-merge {@code extra_body}. Потребители: gRPC-путь воркера
 * ({@code GetLlmCredentials} → {@link #resolveChat}) и медиа-путь коннектора
 * ({@link #resolveForCapability}). Логика в одном месте, чтобы пути не дрейфовали на квотах
 * и extra_body.
 *
 * <p>Не {@code @Transactional}: присоединяется к read-only транзакции вызывающего. Ключ
 * расшифровывается в момент вызова и наружу уходит только в возвращаемом значении —
 * вызывающий не должен его персистить (в т.ч. в DBOS-чекпоинты).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmCredentialsResolver {

    private final AgentLlmRepository agentLlmRepository;
    private final LlmProviderRepository llmProviderRepository;
    private final LlmProviderModelRepository llmProviderModelRepository;
    private final LlmProviderService llmProviderService;
    private final LlmQuotaService llmQuotaService;

    /**
     * Результат резолва. {@code extraBody} — итоговый deep-merge провайдер-уровневого и
     * пер-модельного (модель побеждает, см. {@link ExtraBodyMerge}); пустая map — нет доп. полей.
     * {@code inputModalities} — из строки реестра модели ({@code llm_provider_models}, фолбэк
     * {@code llm_model_defaults} влит write-time); пустой список — модель реестру неизвестна.
     *
     * @param platformFallback {@code true} — выдан платформенный провайдер (у агента нет привязки)
     */
    public record ResolvedLlm(
            LlmProvider provider,
            String model,
            String apiKey,
            Map<String, Object> extraBody,
            List<String> inputModalities,
            boolean platformFallback) {
    }

    /**
     * Chat-модель агентного цикла: первый {@code purpose = CHAT} биндинг агента (по имени),
     * иначе фолбэк на платформенный провайдер с его {@code default_model} (личная привязка
     * всегда побеждает). Биндинги-инструменты (IMAGE/VISION/…) сюда не попадают.
     *
     * @throws NotFoundStatusException      провайдер биндинга исчез / нет ни биндинга, ни платформы
     * @throws LlmProviderDisabledException провайдер биндинга выключен
     * @throws QuotaExceededException       квота провайдера исчерпана
     */
    public ResolvedLlm resolveChat(UUID agentId, UUID userId) {
        LlmProvider provider;
        String model;
        boolean platformFallback = false;

        AgentLlm binding = agentLlmRepository
                .findAllByAgentIdAndPurposeOrderByName(agentId, LlmPurpose.CHAT).stream()
                .findFirst()
                .orElse(null);
        if (binding != null) {
            provider = llmProviderRepository.findById(binding.getLlmProviderId())
                    .orElseThrow(() -> new NotFoundStatusException(
                            "LLM provider not found: " + binding.getLlmProviderId()));
            if (!provider.isEnabled()) {
                throw new LlmProviderDisabledException("LLM provider disabled");
            }
            model = binding.getModel();
        } else {
            // Fallback: платформенный провайдер (уже отфильтрован по enabled + default_model).
            provider = llmProviderService.findUsablePlatformProvider()
                    .orElseThrow(() -> new NotFoundStatusException(
                            "No LLM binding for agent: " + agentId));
            model = provider.getDefaultModel();
            platformFallback = true;
        }

        // Перед каждым LLM-вызовом (креды запрашиваются inline на каждый llm_call).
        llmQuotaService.check(provider, userId, agentId);

        return resolved(provider, model, platformFallback);
    }

    /**
     * Модель-инструмент под назначение (медиа-коннектор). Каскад: явный биндинг агента с этим
     * {@code purpose} (побеждает всегда, реестр не сверяется — выбор пользователя advisory, как в
     * {@code AgentLlmService.validateModel}) → капабилити-матч по реестру enabled-провайдеров
     * пользователя (провайдер chat-биндинга первым — меньше сюрпризов с биллингом; внутри
     * провайдера — первый по имени модели со статусом {@code AVAILABLE} и нужной модальностью) →
     * тот же матч по платформенному провайдеру.
     *
     * @throws IllegalArgumentException     {@code purpose == CHAT} — chat-модель выдаёт {@link #resolveChat}
     * @throws NoCapableModelException      ни биндинга, ни подходящей модели в реестрах
     * @throws LlmProviderDisabledException провайдер явного биндинга выключен (громко, без фолбэка)
     * @throws QuotaExceededException       квота провайдера исчерпана
     */
    public ResolvedLlm resolveForCapability(UUID agentId, UUID userId, LlmPurpose purpose) {
        ModalityRequirement requirement = requirement(purpose);
        LlmProvider provider;
        String model;
        boolean platformFallback = false;

        AgentLlm binding = agentLlmRepository
                .findAllByAgentIdAndPurposeOrderByName(agentId, purpose).stream()
                .findFirst()
                .orElse(null);
        if (binding != null) {
            provider = llmProviderRepository.findById(binding.getLlmProviderId())
                    .orElseThrow(() -> new NotFoundStatusException(
                            "LLM provider not found: " + binding.getLlmProviderId()));
            if (!provider.isEnabled()) {
                throw new LlmProviderDisabledException("LLM provider disabled");
            }
            model = binding.getModel();
        } else {
            Candidate candidate = findCapableModel(agentId, userId, requirement)
                    .orElseThrow(() -> new NoCapableModelException(
                            "No model capable of " + requirement.describe() + " is available: bind one "
                                    + "to the agent (purpose " + purpose + ") or add a provider "
                                    + "whose registry lists such a model"));
            provider = candidate.provider();
            model = candidate.model();
            platformFallback = candidate.platform();
        }

        llmQuotaService.check(provider, userId, agentId);

        return resolved(provider, model, platformFallback);
    }

    /** Финализация: расшифровка ключа + одна выборка строки реестра (extra_body и модальности). */
    private ResolvedLlm resolved(LlmProvider provider, String model, boolean platformFallback) {
        String apiKey = llmProviderService.decryptApiKey(provider);
        LlmProviderModel registryRow = model == null ? null : llmProviderModelRepository
                .findByProviderIdAndModel(provider.getId(), model)
                .orElse(null);
        Map<String, Object> extraBody = ExtraBodyMerge.merge(provider.getExtraBody(),
                registryRow != null ? registryRow.getExtraBody() : null);
        List<String> inputModalities = registryRow != null && registryRow.getInputModalities() != null
                ? List.copyOf(registryRow.getInputModalities()) : List.of();
        return new ResolvedLlm(provider, model, apiKey, extraBody, inputModalities, platformFallback);
    }

    private record Candidate(LlmProvider provider, String model, boolean platform) {
    }

    /** Требование к модели: модальность на входе или выходе ({@code input/output_modalities}). */
    private record ModalityRequirement(String modality, boolean output) {

        String describe() {
            return (output ? "generating " : "reading ") + modality;
        }
    }

    private static ModalityRequirement requirement(LlmPurpose purpose) {
        return switch (purpose) {
            case IMAGE -> new ModalityRequirement("image", true);
            case VISION -> new ModalityRequirement("image", false);
            case AUDIO_IN -> new ModalityRequirement("audio", false);
            case AUDIO_OUT -> new ModalityRequirement("audio", true);
            case CHAT -> throw new IllegalArgumentException("CHAT is resolved via resolveChat");
        };
    }

    private Optional<Candidate> findCapableModel(UUID agentId, UUID userId, ModalityRequirement requirement) {
        List<LlmProvider> candidates = new ArrayList<>(llmProviderRepository
                .findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(LlmProvider::isEnabled)
                .toList());
        // Провайдер chat-биндинга — в голову списка: медиа-вызовы по умолчанию идут туда же,
        // куда пользователь уже направил основной биллинг агента.
        agentLlmRepository.findAllByAgentIdAndPurposeOrderByName(agentId, LlmPurpose.CHAT).stream()
                .findFirst()
                .map(AgentLlm::getLlmProviderId)
                .ifPresent(chatProviderId -> candidates.stream()
                        .filter(p -> p.getId().equals(chatProviderId))
                        .findFirst()
                        .ifPresent(p -> {
                            candidates.remove(p);
                            candidates.add(0, p);
                        }));

        for (LlmProvider provider : candidates) {
            Optional<String> model = firstCapableModel(provider, requirement);
            if (model.isPresent()) {
                return Optional.of(new Candidate(provider, model.get(), false));
            }
        }
        return llmProviderService.findUsablePlatformProvider()
                .flatMap(platform -> firstCapableModel(platform, requirement)
                        .map(model -> new Candidate(platform, model, true)));
    }

    private Optional<String> firstCapableModel(LlmProvider provider, ModalityRequirement requirement) {
        return llmProviderModelRepository.findAllByProviderIdOrderByModel(provider.getId()).stream()
                .filter(m -> m.getStatus() == LlmProviderModelStatus.AVAILABLE)
                .filter(m -> {
                    List<String> modalities = requirement.output()
                            ? m.getOutputModalities() : m.getInputModalities();
                    return modalities != null && modalities.contains(requirement.modality());
                })
                .map(LlmProviderModel::getModel)
                .findFirst();
    }

}
