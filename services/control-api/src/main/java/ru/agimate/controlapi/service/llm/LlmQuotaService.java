package ru.agimate.controlapi.service.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.LlmQuota;
import ru.agimate.controlapi.database.entities.LlmUsageCounter;
import ru.agimate.controlapi.database.enums.UsageSubjectKind;
import ru.agimate.controlapi.database.enums.UsageWindow;
import ru.agimate.controlapi.database.repositories.LlmQuotaRepository;
import ru.agimate.controlapi.database.repositories.LlmUsageCounterRepository;
import ru.agimate.controlapi.service.SystemSkillBootstrap;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Enforcement квот LLM-расхода. Проверка происходит в {@code GetLlmCredentials} — то есть перед
 * КАЖДЫМ LLM-вызовом: превышение возможно максимум на один вызов (чек — до, расход — после).
 * Провайдер без квот не ограничен (BYOK по умолчанию). Чтение счётчика — один indexed lookup
 * на квоту, кэша не требует.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LlmQuotaService {

    private final LlmQuotaRepository quotaRepository;
    private final LlmUsageCounterRepository counterRepository;

    /**
     * Бросает {@link QuotaExceededException} с человекочитаемым сообщением, если хотя бы одна
     * квота провайдера исчерпана для данного пользователя/агента.
     *
     * <p>{@code noRollbackFor}: исчерпание квоты — ожидаемый control-flow, а не сбой БД. Вызов идёт
     * внутри readOnly-транзакции {@code getLlmCredentials}, которая исключение ловит и штатно
     * отвечает {@code RESOURCE_EXHAUSTED}; без этого перехватчик пометил бы общую транзакцию
     * rollback-only и её commit упал бы {@code UnexpectedRollbackException}.
     */
    @Transactional(readOnly = true, noRollbackFor = QuotaExceededException.class)
    public void check(LlmProvider provider, UUID userId, UUID agentId) {
        List<LlmQuota> quotas = quotaRepository.findAllByLlmProviderId(provider.getId());
        if (quotas.isEmpty()) {
            return;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (LlmQuota quota : quotas) {
            UUID subjectId = switch (quota.getSubjectKind()) {
                case USER -> userId;
                case AGENT -> agentId;
                case TOTAL -> LlmUsageCounter.TOTAL_SUBJECT_ID;
            };
            long used = counterRepository
                    .findByLlmProviderIdAndSubjectKindAndSubjectIdAndWindowAndWindowStart(
                            provider.getId(), quota.getSubjectKind(), subjectId,
                            quota.getWindow(), quota.getWindow().windowStart(today))
                    .map(LlmUsageCounter::getTokens)
                    .orElse(0L);
            if (used >= quota.getLimitTokens()) {
                log.info("LLM quota exceeded provider={} kind={} window={} used={} limit={} agent={}",
                        provider.getId(), quota.getSubjectKind(), quota.getWindow(),
                        used, quota.getLimitTokens(), agentId);
                throw new QuotaExceededException(exceededMessage(provider, quota));
            }
        }
    }

    // ---- BYOK-квоты: manage CRUD (владение провайдером проверяет вызывающий контроллер-слой
    // через LlmProviderService.requireOwned) --------------------------------------------------

    public List<LlmQuota> listForProvider(UUID providerId) {
        return quotaRepository.findAllByLlmProviderId(providerId);
    }

    @Transactional
    public LlmQuota create(UUID providerId, UsageSubjectKind subjectKind,
                           UsageWindow window, long limitTokens) {
        if (quotaRepository.existsByLlmProviderIdAndSubjectKindAndWindow(providerId, subjectKind, window)) {
            throw new ConflictStatusException("Quota for this subject and window already exists");
        }
        LlmQuota quota = quotaRepository.save(LlmQuota.builder()
                .llmProviderId(providerId)
                .subjectKind(subjectKind)
                .window(window)
                .limitTokens(limitTokens)
                .build());
        log.info("Created LLM quota provider={} kind={} window={} limit={}",
                providerId, subjectKind, window, limitTokens);
        return quota;
    }

    @Transactional
    public void delete(UUID providerId, UUID quotaId) {
        LlmQuota quota = quotaRepository.findByIdAndLlmProviderId(quotaId, providerId)
                .orElseThrow(() -> new NotFoundStatusException("Quota not found"));
        quotaRepository.delete(quota);
        log.info("Deleted LLM quota id={} provider={}", quotaId, providerId);
    }

    private static String exceededMessage(LlmProvider provider, LlmQuota quota) {
        String window = quota.getWindow() == UsageWindow.DAY ? "Дневной" : "Месячный";
        String reset = quota.getWindow() == UsageWindow.DAY
                ? "Лимит обновится в 00:00 UTC."
                : "Лимит обновится 1-го числа (UTC).";
        if (SystemSkillBootstrap.SYSTEM_USER_ID.equals(provider.getUserId())) {
            return window + " лимит токенов платформенной модели исчерпан. " + reset
                    + " Чтобы снять ограничение, подключите свой LLM-ключ в настройках.";
        }
        return window + " лимит токенов провайдера «" + provider.getName() + "» исчерпан. "
                + reset + " Изменить квоту можно в настройках провайдера.";
    }
}
