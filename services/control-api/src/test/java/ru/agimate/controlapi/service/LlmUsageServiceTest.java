package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.entities.LlmUsageCounter;
import ru.agimate.controlapi.database.repositories.LlmUsageCounterRepository;
import ru.agimate.controlapi.database.repositories.LlmUsageLogRepository;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmUsageService")
class LlmUsageServiceTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PROVIDER_ID = UUID.randomUUID();

    @Mock
    private LlmUsageLogRepository logRepository;
    @Mock
    private LlmUsageCounterRepository counterRepository;

    @InjectMocks
    private LlmUsageService service;

    private static LlmUsageService.UsageReport report(Integer cacheWrite) {
        return new LlmUsageService.UsageReport(
                "wf-llm-1", RUN_ID, AGENT_ID, USER_ID, PROVIDER_ID, "gpt-5-mini",
                100, 20, 50, cacheWrite);
    }

    @Test
    @DisplayName("новый репорт: журнал + 6 инкрементов (USER/AGENT/TOTAL × DAY/MONTH), токены = in+out+cache_write")
    void recordsAndIncrementsAllCounters() {
        when(logRepository.insertIgnoreDuplicate(eq("wf-llm-1"), eq(RUN_ID), eq(AGENT_ID), eq(USER_ID),
                eq(PROVIDER_ID), eq("gpt-5-mini"), eq(100), eq(20), eq(50), eq(30)))
                .thenReturn(1);

        boolean duplicate = service.record(report(30));

        assertFalse(duplicate);
        long expectedTokens = 100 + 20 + 30;
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate monthStart = today.withDayOfMonth(1);

        verify(counterRepository).increment(PROVIDER_ID, "USER", USER_ID, "DAY", today, expectedTokens);
        verify(counterRepository).increment(PROVIDER_ID, "USER", USER_ID, "MONTH", monthStart, expectedTokens);
        verify(counterRepository).increment(PROVIDER_ID, "AGENT", AGENT_ID, "DAY", today, expectedTokens);
        verify(counterRepository).increment(PROVIDER_ID, "AGENT", AGENT_ID, "MONTH", monthStart, expectedTokens);
        verify(counterRepository).increment(PROVIDER_ID, "TOTAL", LlmUsageCounter.TOTAL_SUBJECT_ID,
                "DAY", today, expectedTokens);
        verify(counterRepository).increment(PROVIDER_ID, "TOTAL", LlmUsageCounter.TOTAL_SUBJECT_ID,
                "MONTH", monthStart, expectedTokens);
    }

    @Test
    @DisplayName("cache_write null → токены = in+out; cache_read в метрику не входит")
    void nullCacheWriteExcluded() {
        when(logRepository.insertIgnoreDuplicate(anyString(), any(), any(), any(), any(), anyString(),
                anyInt(), anyInt(), any(), any()))
                .thenReturn(1);

        service.record(report(null));

        verify(counterRepository).increment(eq(PROVIDER_ID), eq("USER"), eq(USER_ID), eq("DAY"),
                any(LocalDate.class), eq(120L));
    }

    @Test
    @DisplayName("дубликат call_id: журнал не вставился → счётчики не трогаются")
    void duplicateSkipsCounters() {
        when(logRepository.insertIgnoreDuplicate(anyString(), any(), any(), any(), any(), anyString(),
                anyInt(), anyInt(), any(), any()))
                .thenReturn(0);

        boolean duplicate = service.record(report(30));

        assertTrue(duplicate);
        verifyNoInteractions(counterRepository);
    }
}
