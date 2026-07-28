package ru.agimate.controlapi.service.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.controller.manage.dto.llm.AgentLlmResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.LlmUsageResponse;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.LlmQuota;
import ru.agimate.controlapi.database.entities.LlmUsageCounter;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.enums.UsageSubjectKind;
import ru.agimate.controlapi.database.enums.UsageWindow;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.database.repositories.LlmQuotaRepository;
import ru.agimate.controlapi.database.repositories.LlmUsageCounterRepository;
import ru.agimate.controlapi.service.LlmProviderService;
import ru.agimate.controlapi.service.SystemSkillBootstrap;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmUsageQueryService — вью расхода для фронта")
class LlmUsageQueryServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private LlmProviderRepository llmProviderRepository;
    @Mock
    private LlmProviderService llmProviderService;
    @Mock
    private LlmQuotaRepository quotaRepository;
    @Mock
    private LlmUsageCounterRepository counterRepository;

    @InjectMocks
    private LlmUsageQueryService service;

    @Test
    @DisplayName("BYOK — перспектива TOTAL с квотой; платформенный — USER без адресуемого id")
    void buildsBothPerspectives() {
        UUID byokId = UUID.randomUUID();
        LlmProvider byok = LlmProvider.builder()
                .id(byokId).userId(USER_ID).name("my-openrouter")
                .providerType(LlmProviderType.OPENAI_COMPATIBLE).enabled(true)
                .build();
        LlmProvider platform = LlmProvider.builder()
                .id(UUID.randomUUID()).userId(SystemSkillBootstrap.SYSTEM_USER_ID).name("platform")
                .providerType(LlmProviderType.OPENAI_COMPATIBLE).purposePriority(Map.of(LlmPurpose.CHAT, List.of("gpt-5-mini"))).enabled(true)
                .build();
        when(llmProviderRepository.findAllByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(byok));
        when(llmProviderService.findUsablePlatformProvider()).thenReturn(Optional.of(platform));

        // BYOK: дневная TOTAL-квота 1000, израсходовано 300 за день
        when(quotaRepository.findAllByLlmProviderId(byokId)).thenReturn(List.of(
                LlmQuota.builder().llmProviderId(byokId)
                        .subjectKind(UsageSubjectKind.TOTAL).window(UsageWindow.DAY).limitTokens(1000L)
                        .build()));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(counterRepository.findByLlmProviderIdAndSubjectKindAndSubjectIdAndWindowAndWindowStart(
                byokId, UsageSubjectKind.TOTAL, LlmUsageCounter.TOTAL_SUBJECT_ID, UsageWindow.DAY, today))
                .thenReturn(Optional.of(LlmUsageCounter.builder().tokens(300L).requests(7).build()));
        when(counterRepository.findByLlmProviderIdAndSubjectKindAndSubjectIdAndWindowAndWindowStart(
                eq(byokId), eq(UsageSubjectKind.TOTAL), eq(LlmUsageCounter.TOTAL_SUBJECT_ID),
                eq(UsageWindow.MONTH), any())).thenReturn(Optional.empty());

        // платформенный: без квот и счётчиков (нулевой расход)
        when(quotaRepository.findAllByLlmProviderId(platform.getId())).thenReturn(List.of());
        when(counterRepository.findByLlmProviderIdAndSubjectKindAndSubjectIdAndWindowAndWindowStart(
                eq(platform.getId()), eq(UsageSubjectKind.USER), eq(USER_ID), any(), any()))
                .thenReturn(Optional.empty());

        List<LlmUsageResponse> result = service.usageForUser(USER_ID);

        assertEquals(2, result.size());

        LlmUsageResponse byokUsage = result.get(0);
        assertEquals(byokId, byokUsage.llmProviderId());
        assertEquals(AgentLlmResponse.Source.USER, byokUsage.source());
        LlmUsageResponse.WindowUsage day = byokUsage.windows().stream()
                .filter(w -> w.window() == UsageWindow.DAY).findFirst().orElseThrow();
        assertEquals(300L, day.usedTokens());
        assertEquals(7, day.requests());
        assertEquals(1000L, day.limitTokens());
        assertEquals(700L, day.remainingTokens());
        LlmUsageResponse.WindowUsage month = byokUsage.windows().stream()
                .filter(w -> w.window() == UsageWindow.MONTH).findFirst().orElseThrow();
        assertEquals(0L, month.usedTokens());
        assertNull(month.limitTokens(), "квоты на месяц нет → лимит null");
        assertNull(month.remainingTokens());

        LlmUsageResponse platformUsage = result.get(1);
        assertNull(platformUsage.llmProviderId(), "платформенный провайдер не адресуем");
        assertEquals(AgentLlmResponse.Source.PLATFORM, platformUsage.source());
        assertEquals(0L, platformUsage.windows().get(0).usedTokens());
    }
}
