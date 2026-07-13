package ru.agimate.controlapi.service.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.LlmQuota;
import ru.agimate.controlapi.database.entities.LlmUsageCounter;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.UsageSubjectKind;
import ru.agimate.controlapi.database.enums.UsageWindow;
import ru.agimate.controlapi.database.repositories.LlmQuotaRepository;
import ru.agimate.controlapi.database.repositories.LlmUsageCounterRepository;
import ru.agimate.controlapi.service.SystemSkillBootstrap;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmQuotaService")
class LlmQuotaServiceTest {

    private static final UUID PROVIDER_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    @Mock
    private LlmQuotaRepository quotaRepository;
    @Mock
    private LlmUsageCounterRepository counterRepository;

    @InjectMocks
    private LlmQuotaService service;

    private static LlmProvider platformProvider() {
        return LlmProvider.builder()
                .id(PROVIDER_ID)
                .userId(SystemSkillBootstrap.SYSTEM_USER_ID)
                .name("platform")
                .providerType(LlmProviderType.OPENAI_COMPATIBLE)
                .enabled(true)
                .build();
    }

    private static LlmProvider byokProvider() {
        return LlmProvider.builder()
                .id(PROVIDER_ID)
                .userId(USER_ID)
                .name("my-openrouter")
                .providerType(LlmProviderType.OPENAI_COMPATIBLE)
                .enabled(true)
                .build();
    }

    private static LlmQuota quota(UsageSubjectKind kind, UsageWindow window, long limit) {
        return LlmQuota.builder()
                .id(UUID.randomUUID())
                .llmProviderId(PROVIDER_ID)
                .subjectKind(kind)
                .window(window)
                .limitTokens(limit)
                .build();
    }

    private void stubCounter(UsageSubjectKind kind, UUID subjectId, UsageWindow window, Long tokens) {
        LocalDate start = window.windowStart(LocalDate.now(ZoneOffset.UTC));
        Optional<LlmUsageCounter> counter = tokens == null
                ? Optional.empty()
                : Optional.of(LlmUsageCounter.builder().tokens(tokens).requests(1).build());
        when(counterRepository.findByLlmProviderIdAndSubjectKindAndSubjectIdAndWindowAndWindowStart(
                PROVIDER_ID, kind, subjectId, window, start)).thenReturn(counter);
    }

    @Nested
    @DisplayName("check — enforcement")
    class Check {

        @Test
        @DisplayName("нет квот → без ограничений, счётчики не читаются")
        void noQuotasNoChecks() {
            when(quotaRepository.findAllByLlmProviderId(PROVIDER_ID)).thenReturn(List.of());

            assertDoesNotThrow(() -> service.check(byokProvider(), USER_ID, AGENT_ID));
            verifyNoInteractions(counterRepository);
        }

        @Test
        @DisplayName("под лимитом → проходит; счётчика ещё нет → расход 0")
        void underLimitPasses() {
            when(quotaRepository.findAllByLlmProviderId(PROVIDER_ID)).thenReturn(List.of(
                    quota(UsageSubjectKind.USER, UsageWindow.DAY, 1000),
                    quota(UsageSubjectKind.TOTAL, UsageWindow.MONTH, 50_000)));
            stubCounter(UsageSubjectKind.USER, USER_ID, UsageWindow.DAY, 999L);
            stubCounter(UsageSubjectKind.TOTAL, LlmUsageCounter.TOTAL_SUBJECT_ID, UsageWindow.MONTH, null);

            assertDoesNotThrow(() -> service.check(platformProvider(), USER_ID, AGENT_ID));
        }

        @Test
        @DisplayName("платформенный, used == limit → QuotaExceededException с текстом про свой ключ")
        void platformLimitReached() {
            when(quotaRepository.findAllByLlmProviderId(PROVIDER_ID)).thenReturn(List.of(
                    quota(UsageSubjectKind.USER, UsageWindow.DAY, 1000)));
            stubCounter(UsageSubjectKind.USER, USER_ID, UsageWindow.DAY, 1000L);

            QuotaExceededException e = assertThrows(QuotaExceededException.class,
                    () -> service.check(platformProvider(), USER_ID, AGENT_ID));
            assertTrue(e.getMessage().contains("платформенной модели"));
            assertTrue(e.getMessage().contains("00:00 UTC"));
            assertTrue(e.getMessage().contains("подключите свой LLM-ключ"));
        }

        @Test
        @DisplayName("BYOK, AGENT-квота: субъект — агент, текст — про настройки провайдера")
        void byokAgentQuota() {
            when(quotaRepository.findAllByLlmProviderId(PROVIDER_ID)).thenReturn(List.of(
                    quota(UsageSubjectKind.AGENT, UsageWindow.MONTH, 500)));
            stubCounter(UsageSubjectKind.AGENT, AGENT_ID, UsageWindow.MONTH, 700L);

            QuotaExceededException e = assertThrows(QuotaExceededException.class,
                    () -> service.check(byokProvider(), USER_ID, AGENT_ID));
            assertTrue(e.getMessage().contains("my-openrouter"));
            assertTrue(e.getMessage().contains("Месячный"));
        }
    }

    @Nested
    @DisplayName("CRUD BYOK-квот")
    class Crud {

        @Test
        @DisplayName("дубликат (провайдер, субъект, окно) → 409")
        void duplicateConflicts() {
            when(quotaRepository.existsByLlmProviderIdAndSubjectKindAndWindow(
                    PROVIDER_ID, UsageSubjectKind.TOTAL, UsageWindow.DAY)).thenReturn(true);

            assertThrows(ConflictStatusException.class,
                    () -> service.create(PROVIDER_ID, UsageSubjectKind.TOTAL, UsageWindow.DAY, 1000));
        }

        @Test
        @DisplayName("создание сохраняет все поля")
        void createPersists() {
            when(quotaRepository.existsByLlmProviderIdAndSubjectKindAndWindow(any(), any(), any()))
                    .thenReturn(false);
            when(quotaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            LlmQuota created = service.create(PROVIDER_ID, UsageSubjectKind.TOTAL, UsageWindow.DAY, 1000);

            verify(quotaRepository).save(any());
            assertTrue(created.getLimitTokens() == 1000
                    && created.getSubjectKind() == UsageSubjectKind.TOTAL
                    && created.getWindow() == UsageWindow.DAY);
        }

        @Test
        @DisplayName("удаление чужого/несуществующего id → 404")
        void deleteMissing() {
            when(quotaRepository.findByIdAndLlmProviderId(any(), eq(PROVIDER_ID)))
                    .thenReturn(Optional.empty());

            assertThrows(NotFoundStatusException.class,
                    () -> service.delete(PROVIDER_ID, UUID.randomUUID()));
        }
    }
}
