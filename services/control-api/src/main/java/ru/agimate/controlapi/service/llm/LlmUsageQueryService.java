package ru.agimate.controlapi.service.llm;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.controller.manage.dto.llm.AgentLlmResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.LlmUsageResponse;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.LlmQuota;
import ru.agimate.controlapi.database.entities.LlmUsageCounter;
import ru.agimate.controlapi.database.enums.UsageSubjectKind;
import ru.agimate.controlapi.database.enums.UsageWindow;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.database.repositories.LlmQuotaRepository;
import ru.agimate.controlapi.database.repositories.LlmUsageCounterRepository;
import ru.agimate.controlapi.service.LlmProviderService;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Вью расхода токенов для фронта («осталось N из M»). Перспектива по типу провайдера:
 * BYOK — TOTAL (весь провайдер, потолок кошелька), платформенный — USER (лимит «каждому
 * пользователю»). Per-agent разрезы здесь не показываются (enforcement их учитывает).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LlmUsageQueryService {

    private final LlmProviderRepository llmProviderRepository;
    private final LlmProviderService llmProviderService;
    private final LlmQuotaRepository quotaRepository;
    private final LlmUsageCounterRepository counterRepository;

    public List<LlmUsageResponse> usageForUser(UUID userId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LlmUsageResponse> result = new ArrayList<>();
        for (LlmProvider provider : llmProviderRepository.findAllByUserIdOrderByCreatedAtDesc(userId)) {
            result.add(build(provider, UsageSubjectKind.TOTAL, LlmUsageCounter.TOTAL_SUBJECT_ID,
                    AgentLlmResponse.Source.USER, today));
        }
        llmProviderService.findUsablePlatformProvider().ifPresent(platform ->
                result.add(build(platform, UsageSubjectKind.USER, userId,
                        AgentLlmResponse.Source.PLATFORM, today)));
        return result;
    }

    private LlmUsageResponse build(LlmProvider provider, UsageSubjectKind kind, UUID subjectId,
                                   AgentLlmResponse.Source source, LocalDate today) {
        Map<UsageWindow, Long> limits = quotaRepository.findAllByLlmProviderId(provider.getId()).stream()
                .filter(q -> q.getSubjectKind() == kind)
                .collect(Collectors.toMap(LlmQuota::getWindow, LlmQuota::getLimitTokens, (a, b) -> a));

        List<LlmUsageResponse.WindowUsage> windows = new ArrayList<>();
        for (UsageWindow window : UsageWindow.values()) {
            LocalDate windowStart = window.windowStart(today);
            LlmUsageCounter counter = counterRepository
                    .findByLlmProviderIdAndSubjectKindAndSubjectIdAndWindowAndWindowStart(
                            provider.getId(), kind, subjectId, window, windowStart)
                    .orElse(null);
            long used = counter != null ? counter.getTokens() : 0L;
            int requests = counter != null ? counter.getRequests() : 0;
            Long limit = limits.get(window);
            windows.add(new LlmUsageResponse.WindowUsage(
                    window, windowStart, used, requests,
                    limit, limit != null ? Math.max(0, limit - used) : null));
        }
        return new LlmUsageResponse(
                source == AgentLlmResponse.Source.PLATFORM ? null : provider.getId(),
                provider.getName(), source, windows);
    }
}
