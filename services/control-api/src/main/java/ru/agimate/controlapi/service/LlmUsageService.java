package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.entities.LlmUsageCounter;
import ru.agimate.controlapi.database.enums.UsageSubjectKind;
import ru.agimate.controlapi.database.enums.UsageWindow;
import ru.agimate.controlapi.database.repositories.LlmUsageCounterRepository;
import ru.agimate.controlapi.database.repositories.LlmUsageLogRepository;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Accounting of LLM token usage. One transaction per report: an idempotent insert into the journal (by
 * {@code call_id}) plus increments of the counters for every subject (USER/AGENT/TOTAL) in both windows
 * (DAY/MONTH) — the statistics are complete from day one and do not depend on when a quota was created.
 *
 * <p>The metric: {@code input + output + cache_write}; cache_read is not counted — cache hits are
 * nearly free, and penalising them would be perverse.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LlmUsageService {

    private final LlmUsageLogRepository logRepository;
    private final LlmUsageCounterRepository counterRepository;

    public record UsageReport(
            String callId,
            UUID runId,
            UUID agentId,
            UUID userId,
            UUID providerId,
            String model,
            int inputTokens,
            int outputTokens,
            Integer cacheReadTokens,
            Integer cacheWriteTokens) {
    }

    /**
     * @return {@code true} when {@code call_id} had already been accounted for (a replay or repeat) — nothing was incremented
     */
    @Transactional
    public boolean record(UsageReport report) {
        int inserted = logRepository.insertIgnoreDuplicate(
                report.callId(), report.runId(), report.agentId(), report.userId(),
                report.providerId(), report.model(),
                report.inputTokens(), report.outputTokens(),
                report.cacheReadTokens(), report.cacheWriteTokens());
        if (inserted == 0) {
            log.debug("duplicate LLM usage report call={}", report.callId());
            return true;
        }

        long tokens = (long) report.inputTokens() + report.outputTokens()
                + (report.cacheWriteTokens() != null ? report.cacheWriteTokens() : 0);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Map<UsageSubjectKind, UUID> subjects = Map.of(
                UsageSubjectKind.USER, report.userId(),
                UsageSubjectKind.AGENT, report.agentId(),
                UsageSubjectKind.TOTAL, LlmUsageCounter.TOTAL_SUBJECT_ID);
        for (var subject : subjects.entrySet()) {
            for (UsageWindow window : UsageWindow.values()) {
                counterRepository.increment(report.providerId(), subject.getKey().name(),
                        subject.getValue(), window.name(), window.windowStart(today), tokens);
            }
        }
        return false;
    }
}
