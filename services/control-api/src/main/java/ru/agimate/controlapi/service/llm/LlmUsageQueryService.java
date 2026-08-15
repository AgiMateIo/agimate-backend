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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A view of token usage for the frontend («N of M left»). The perspective depends on the provider's
 * type: BYOK — TOTAL (the whole provider, the wallet's ceiling), platform — USER (the «per user»
 * limit). Per-agent breakdowns are not shown here (enforcement does account for them).
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
        List<Subject> subjects = subjects(userId);
        if (subjects.isEmpty()) {
            return List.of();
        }

        List<UUID> providerIds = subjects.stream().map(s -> s.provider().getId()).distinct().toList();
        Map<QuotaKey, Long> limits = quotaRepository.findAllByLlmProviderIdIn(providerIds).stream()
                .collect(Collectors.toMap(
                        quota -> new QuotaKey(quota.getLlmProviderId(), quota.getSubjectKind(), quota.getWindow()),
                        LlmQuota::getLimitTokens,
                        (a, b) -> a));
        Map<CounterKey, LlmUsageCounter> counters = counterRepository
                .findForSubjects(providerIds,
                        subjects.stream().map(Subject::subjectId).collect(Collectors.toSet()),
                        windowStarts(today))
                .stream()
                .collect(Collectors.toMap(LlmUsageQueryService::keyOf, Function.identity()));

        return subjects.stream().map(subject -> build(subject, limits, counters, today)).toList();
    }

    /** Which subject each provider is asked about — the perspective the class comment describes. */
    private List<Subject> subjects(UUID userId) {
        List<Subject> subjects = new ArrayList<>();
        for (LlmProvider provider : llmProviderRepository.findAllByUserIdOrderByCreatedAtDesc(userId)) {
            subjects.add(new Subject(provider, UsageSubjectKind.TOTAL, LlmUsageCounter.TOTAL_SUBJECT_ID,
                    AgentLlmResponse.Source.USER));
        }
        llmProviderService.findUsablePlatformProvider().ifPresent(platform ->
                subjects.add(new Subject(platform, UsageSubjectKind.USER, userId,
                        AgentLlmResponse.Source.PLATFORM)));
        return subjects;
    }

    private static Set<LocalDate> windowStarts(LocalDate today) {
        return Arrays.stream(UsageWindow.values())
                .map(window -> window.windowStart(today))
                .collect(Collectors.toSet());
    }

    private LlmUsageResponse build(Subject subject, Map<QuotaKey, Long> limits,
                                   Map<CounterKey, LlmUsageCounter> counters, LocalDate today) {
        LlmProvider provider = subject.provider();
        List<LlmUsageResponse.WindowUsage> windows = new ArrayList<>();
        for (UsageWindow window : UsageWindow.values()) {
            LocalDate windowStart = window.windowStart(today);
            LlmUsageCounter counter = counters.get(new CounterKey(
                    provider.getId(), subject.kind(), subject.subjectId(), window, windowStart));
            long used = counter != null ? counter.getTokens() : 0L;
            int requests = counter != null ? counter.getRequests() : 0;
            Long limit = limits.get(new QuotaKey(provider.getId(), subject.kind(), window));
            windows.add(new LlmUsageResponse.WindowUsage(
                    window, windowStart, used, requests,
                    limit, limit != null ? Math.max(0, limit - used) : null));
        }
        return new LlmUsageResponse(
                subject.source() == AgentLlmResponse.Source.PLATFORM ? null : provider.getId(),
                provider.getName(), subject.source(), windows);
    }

    private static CounterKey keyOf(LlmUsageCounter counter) {
        return new CounterKey(counter.getLlmProviderId(), counter.getSubjectKind(),
                counter.getSubjectId(), counter.getWindow(), counter.getWindowStart());
    }

    private record Subject(LlmProvider provider, UsageSubjectKind kind, UUID subjectId,
                           AgentLlmResponse.Source source) {
    }

    private record QuotaKey(UUID providerId, UsageSubjectKind kind, UsageWindow window) {
    }

    /**
     * {@code windowStart} belongs in the key: on the first of the month the two windows start on the
     * same date, and a DAY counter from that day would otherwise collide with the current one.
     */
    private record CounterKey(UUID providerId, UsageSubjectKind kind, UUID subjectId,
                              UsageWindow window, LocalDate windowStart) {
    }
}
