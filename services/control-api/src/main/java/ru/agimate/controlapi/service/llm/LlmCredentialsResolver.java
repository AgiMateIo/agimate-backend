package ru.agimate.controlapi.service.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.LlmProviderModel;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.repositories.AgentLlmRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderModelRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.service.LlmProviderService;

import java.util.Map;
import java.util.UUID;

/**
 * Единый конвейер выдачи LLM-кредов: выбор провайдера/модели → {@code enabled}-проверка →
 * квота → расшифровка ключа → deep-merge {@code extra_body}. Потребители: gRPC-путь воркера
 * ({@code GetLlmCredentials} → {@link #resolveChat}) и медиа-путь коннектора (резолв по
 * капабилити — следующий шаг). Логика в одном месте, чтобы пути не дрейфовали на квотах
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
     *
     * @param platformFallback {@code true} — выдан платформенный провайдер (у агента нет привязки)
     */
    public record ResolvedLlm(
            LlmProvider provider,
            String model,
            String apiKey,
            Map<String, Object> extraBody,
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

        String apiKey = llmProviderService.decryptApiKey(provider);
        return new ResolvedLlm(provider, model, apiKey, resolveExtraBody(provider, model), platformFallback);
    }

    private Map<String, Object> resolveExtraBody(LlmProvider provider, String model) {
        Map<String, Object> perModel = model == null ? null : llmProviderModelRepository
                .findByProviderIdAndModel(provider.getId(), model)
                .map(LlmProviderModel::getExtraBody)
                .orElse(null);
        return ExtraBodyMerge.merge(provider.getExtraBody(), perModel);
    }
}
