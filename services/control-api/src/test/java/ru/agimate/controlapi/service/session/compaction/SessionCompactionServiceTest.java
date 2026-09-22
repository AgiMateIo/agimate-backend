package ru.agimate.controlapi.service.session.compaction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.AgentRunTurn;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.LlmProviderModel;
import ru.agimate.controlapi.database.entities.LlmUsageLog;
import ru.agimate.controlapi.database.enums.AgentSessionScope;
import ru.agimate.controlapi.database.enums.AgentTurnRole;
import ru.agimate.controlapi.database.enums.SessionTitleSource;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentRunTurnRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.database.repositories.LlmModelDefaultsRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderModelRepository;
import ru.agimate.controlapi.database.repositories.LlmUsageLogRepository;
import ru.agimate.controlapi.service.LlmUsageService;
import ru.agimate.controlapi.service.llm.ChatCompletionsHttp;
import ru.agimate.controlapi.service.llm.LlmCredentialsResolver;
import ru.agimate.controlapi.service.llm.LlmCredentialsResolver.ResolvedLlm;
import ru.agimate.controlapi.service.session.compaction.SessionCompactionService.Retelling;
import ru.agimate.controlapi.service.session.compaction.SessionCompactionService.Work;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionCompactionService — сводка и заголовок разговора")
class SessionCompactionServiceTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PROVIDER_ID = UUID.randomUUID();
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 22, 12, 0);

    @Mock private AgentSessionRepository sessionRepository;
    @Mock private AgentRunRepository runRepository;
    @Mock private AgentRunTurnRepository turnRepository;
    @Mock private LlmUsageLogRepository usageLogRepository;
    @Mock private LlmProviderModelRepository providerModelRepository;
    @Mock private LlmModelDefaultsRepository modelDefaultsRepository;
    @Mock private LlmCredentialsResolver credentialsResolver;
    @Mock private ChatCompletionsHttp http;
    @Mock private LlmUsageService usageService;
    @Mock private SessionCompactionWriter writer;

    private SessionCompactionService service;

    @BeforeEach
    void setUp() {
        service = new SessionCompactionService(sessionRepository, runRepository, turnRepository, usageLogRepository,
                providerModelRepository, modelDefaultsRepository, credentialsResolver, http, usageService, writer);
    }

    private static AgentSession session(SessionTitleSource source) {
        return AgentSession.builder()
                .id(SESSION_ID).agentId(AGENT_ID).userId(USER_ID)
                .scope(AgentSessionScope.CHANNEL).titleSource(source)
                .build();
    }

    private static List<UUID> runs(int count) {
        return IntStream.range(0, count).mapToObj(i -> UUID.randomUUID()).toList();
    }

    /** The run's first call took {@code tokens} of a 100k window. */
    private void firstCall(UUID runId, int tokens) {
        AgentRun run = AgentRun.builder().id(runId).build();
        run.setCreatedAt(T0);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        AgentRunTurn answer = AgentRunTurn.builder().runId(runId).turnIndex(1)
                .role(AgentTurnRole.ASSISTANT).callId("call-1").build();
        when(turnRepository.findFirstByRunIdAndRoleAndTurnIndexGreaterThanEqualOrderByTurnIndexAsc(
                runId, AgentTurnRole.ASSISTANT, 0)).thenReturn(Optional.of(answer));
        when(usageLogRepository.findByCallId("call-1")).thenReturn(Optional.of(LlmUsageLog.builder()
                .llmProviderId(PROVIDER_ID).model("m").inputTokens(tokens).build()));
        when(providerModelRepository.findByLlmProviderIdAndModel(PROVIDER_ID, "m"))
                .thenReturn(Optional.of(LlmProviderModel.builder().contextWindow(100_000).build()));
    }

    private void routineAnswers(String content) {
        LlmProvider provider = LlmProvider.builder().build();
        provider.setId(PROVIDER_ID);
        when(credentialsResolver.resolveRoutine(AGENT_ID, USER_ID)).thenReturn(new ResolvedLlm(
                provider, "cheap", "key", Map.of(), List.of(), List.of(), Map.of(), false));
        when(http.post(eq(provider), eq("key"), any(), any())).thenReturn(Map.of("choices",
                List.of(Map.of("message", Map.of("content", content)))));
    }

    @Nested
    @DisplayName("Что пересказывается")
    class WhatIsRetold {

        @Test
        @DisplayName("один ран — сжимать нечего: дословным должен остаться хотя бы один")
        void singleRunStays() {
            assertTrue(SessionCompactionService.retelling(runs(1)).isEmpty());
        }

        @Test
        @DisplayName("мало ранов — дословной остаётся половина, якорь — старший из неё")
        void halfStaysWhenFew() {
            List<UUID> newestFirst = runs(4);

            Retelling retelling = SessionCompactionService.retelling(newestFirst).orElseThrow();

            assertEquals(newestFirst.get(1), retelling.anchorRunId());
            assertEquals(List.of(newestFirst.get(3), newestFirst.get(2)), retelling.runs());
        }

        @Test
        @DisplayName("много ранов — дословных десять, остальные в пересказ от старых к новым")
        void tenStayWhenMany() {
            List<UUID> newestFirst = runs(30);

            Retelling retelling = SessionCompactionService.retelling(newestFirst).orElseThrow();

            assertEquals(newestFirst.get(9), retelling.anchorRunId());
            assertEquals(20, retelling.runs().size());
            assertEquals(newestFirst.get(29), retelling.runs().get(0));
            assertEquals(newestFirst.get(10), retelling.runs().get(19));
        }
    }

    @Nested
    @DisplayName("Что пора делать")
    class Due {

        @Test
        @DisplayName("сессия субагента не обслуживается совсем")
        void childIsNotKept() {
            AgentSession child = session(null);
            child.setParentSessionId(UUID.randomUUID());

            assertEquals(Set.of(), service.due(child, UUID.randomUUID()));
            verifyNoInteractions(runRepository);
        }

        @Test
        @DisplayName("заглушка и пустой заголовок — пора заголовок; переименованный руками — нет")
        void titleUnlessRenamed() {
            UUID runId = UUID.randomUUID();
            when(runRepository.findById(runId)).thenReturn(Optional.empty());

            assertEquals(Set.of(Work.TITLE), service.due(session(SessionTitleSource.HINT), runId));
            assertEquals(Set.of(Work.TITLE), service.due(session(null), runId));
            assertEquals(Set.of(), service.due(session(SessionTitleSource.USER), runId));
        }

        @Test
        @DisplayName("первый вызов рана больше 85% окна — пора сводка")
        void summaryOverThreshold() {
            UUID runId = UUID.randomUUID();
            firstCall(runId, 90_000);

            assertEquals(Set.of(Work.SUMMARY), service.due(session(SessionTitleSource.GENERATED), runId));
        }

        @Test
        @DisplayName("ниже порога — сводки нет")
        void noSummaryUnderThreshold() {
            UUID runId = UUID.randomUUID();
            firstCall(runId, 80_000);

            assertEquals(Set.of(), service.due(session(SessionTitleSource.GENERATED), runId));
        }

        @Test
        @DisplayName("сводка написана после старта рана — он уже учтён")
        void summaryAfterRunCountsIt() {
            UUID runId = UUID.randomUUID();
            AgentRun run = AgentRun.builder().id(runId).build();
            run.setCreatedAt(T0);
            when(runRepository.findById(runId)).thenReturn(Optional.of(run));
            AgentRunTurn summary = AgentRunTurn.builder().role(AgentTurnRole.SYSTEM).build();
            summary.setCreatedAt(T0.plusMinutes(1));
            when(turnRepository.findLatestSummary(SESSION_ID)).thenReturn(Optional.of(summary));

            assertEquals(Set.of(), service.due(session(SessionTitleSource.GENERATED), runId));
            verify(usageLogRepository, never()).findByCallId(any());
        }
    }

    @Nested
    @DisplayName("Джоба")
    class Maintain {

        @Test
        @DisplayName("над порогом: один вызов, сводка на якоре и заголовок одной записью")
        void summaryAndTitle() {
            AgentSession session = session(SessionTitleSource.GENERATED);
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
            List<UUID> newestFirst = runs(4);
            when(runRepository.findHistoryRunIds(eq(SESSION_ID), any())).thenReturn(newestFirst);
            firstCall(newestFirst.get(0), 95_000);
            when(turnRepository.findByRunIdInOrderByRunIdAscTurnIndexAsc(anyList())).thenReturn(List.of(
                    AgentRunTurn.builder().runId(newestFirst.get(3)).turnIndex(0)
                            .role(AgentTurnRole.USER).text("найди поставщика").build()));
            routineAnswers("```json\n{\"summary\": \"Ищем поставщика.\", \"title\": \"Поставщик\"}\n```");

            Set<Work> done = service.maintain(SESSION_ID);

            assertEquals(Set.of(Work.SUMMARY, Work.TITLE), done);
            verify(writer).write(SESSION_ID, AGENT_ID, newestFirst.get(1), "Ищем поставщика.", "cheap", "Поставщик");
            ArgumentCaptor<LlmUsageService.UsageReport> usage = ArgumentCaptor.forClass(LlmUsageService.UsageReport.class);
            verify(usageService).record(usage.capture());
            assertTrue(usage.getValue().callId().startsWith("routine:"));
            assertNull(usage.getValue().runId());
        }

        @Test
        @DisplayName("только заголовок: сводка не пишется, якоря нет")
        void titleOnly() {
            AgentSession session = session(SessionTitleSource.HINT);
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
            List<UUID> newestFirst = runs(1);
            when(runRepository.findHistoryRunIds(eq(SESSION_ID), any())).thenReturn(newestFirst);
            when(runRepository.findById(newestFirst.get(0))).thenReturn(Optional.empty());
            when(turnRepository.findByRunIdInOrderByRunIdAscTurnIndexAsc(anyList())).thenReturn(List.of());
            routineAnswers("{\"title\": \"  Выбор\\n поставщика  \"}");

            Set<Work> done = service.maintain(SESSION_ID);

            assertEquals(Set.of(Work.TITLE), done);
            verify(writer).write(eq(SESSION_ID), eq(AGENT_ID), isNull(), isNull(), eq("cheap"), eq("Выбор поставщика"));
        }

        @Test
        @DisplayName("делать нечего — модель не зовётся")
        void nothingDue() {
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session(SessionTitleSource.USER)));
            List<UUID> newestFirst = runs(2);
            when(runRepository.findHistoryRunIds(eq(SESSION_ID), any())).thenReturn(newestFirst);
            when(runRepository.findById(newestFirst.get(0))).thenReturn(Optional.empty());

            assertEquals(Set.of(), service.maintain(SESSION_ID));
            verifyNoInteractions(credentialsResolver, http, writer);
        }
    }

    @Nested
    @DisplayName("Ответ модели")
    class Answer {

        @Test
        @DisplayName("JSON находится и внутри забора, и среди слов")
        void findsTheObject() {
            assertEquals("S", SessionCompactionService.parseAnswer("Вот: {\"summary\": \"S\"} — готово").get("summary"));
        }

        @Test
        @DisplayName("без JSON — ошибка, а не пустая сводка")
        void noObjectFails() {
            assertThrows(IllegalStateException.class, () -> SessionCompactionService.parseAnswer("не могу"));
        }

        @Test
        @DisplayName("заголовок — одна строка не длиннее предела; пустой — null")
        void titleIsOneLine() {
            String longTitle = "x".repeat(200);
            assertEquals(80, SessionCompactionService.text(Map.of("title", longTitle), "title", 80).length());
            assertNull(SessionCompactionService.text(Map.of("title", "  "), "title", 80));
            List<Object> none = new ArrayList<>();
            assertNull(SessionCompactionService.text(Map.of("title", none), "title", 80));
        }
    }
}
