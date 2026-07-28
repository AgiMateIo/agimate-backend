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
 * Enforcement of LLM usage quotas. The check happens in {@code GetLlmCredentials} — that is, before
 * EVERY LLM call: an overrun is possible by at most one call (the check comes before, the spending
 * after). A provider with no quotas is unlimited (BYOK by default). Reading a counter is one indexed
 * lookup per quota and needs no cache.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LlmQuotaService {

    private final LlmQuotaRepository quotaRepository;
    private final LlmUsageCounterRepository counterRepository;

    /**
     * Throws a {@link QuotaExceededException} with a human-readable message when at least one of the
     * provider's quotas is exhausted for that user or agent.
     *
     * <p>{@code noRollbackFor}: exhausting a quota is expected control flow, not a database failure.
     * The call happens inside the read-only transaction of {@code getLlmCredentials}, which catches
     * the exception and answers {@code RESOURCE_EXHAUSTED} as designed; without this the interceptor
     * would mark the enclosing transaction rollback-only and its commit would fail with
     * {@code UnexpectedRollbackException}.
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

    // ---- BYOK quotas: the manage CRUD (ownership of the provider is checked by the calling controller layer
    // through LlmProviderService.requireOwned) ------------------------------------------------

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
    public LlmQuota updateLimit(UUID providerId, UUID quotaId, long limitTokens) {
        LlmQuota quota = quotaRepository.findByIdAndLlmProviderId(quotaId, providerId)
                .orElseThrow(() -> new NotFoundStatusException("Quota not found"));
        quota.setLimitTokens(limitTokens);
        quota = quotaRepository.save(quota);
        log.info("Updated LLM quota id={} provider={} limit={}", quotaId, providerId, limitTokens);
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
