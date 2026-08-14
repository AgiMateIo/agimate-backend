package ru.agimate.controlapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.controller.manage.dto.AgentRunPromptResponse;
import ru.agimate.controlapi.controller.manage.dto.AgentRunResponse;
import ru.agimate.controlapi.controller.manage.dto.RunUsageResponse;
import ru.agimate.controlapi.database.projections.AgentRunProjection;
import ru.agimate.controlapi.database.projections.RunUsageProjection;
import ru.agimate.controlapi.controller.manage.dto.AgentRunTurnResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.AgentRunTurn;
import ru.agimate.controlapi.database.entities.LlmUsageLog;
import ru.agimate.controlapi.database.enums.AgentTurnRole;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentRunTurnRepository;
import ru.agimate.controlapi.database.repositories.LlmUsageLogRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentRunQueryService")
class AgentRunQueryServiceTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock private AgentRunRepository agentRunRepository;
    @Mock private AgentRunTurnRepository turnRepository;
    @Mock private LlmUsageLogRepository usageLogRepository;

    private AgentRunQueryService service;

    @BeforeEach
    void setUp() {
        service = new AgentRunQueryService(agentRunRepository, turnRepository, usageLogRepository);
    }

    private AgentRun run(UUID ownerId) {
        AgentRun run = AgentRun.builder()
                .agent(Agent.builder().id(UUID.randomUUID()).userId(ownerId).build())
                .destination("GENERIC")
                .build();
        run.setId(RUN_ID);
        return run;
    }

    @Nested
    @DisplayName("listRuns")
    class ListRuns {

        @Test
        @DisplayName("пустая строка фильтра — это отсутствие фильтра, а не поиск по пустой строке")
        void blankFiltersBecomeNull() {
            when(agentRunRepository.findRunsWithFilters(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any())).thenReturn(Page.empty());

            service.listRuns(USER_ID, null, SESSION_ID, null, "  ", "", "  ", null, 0, 20);

            verify(agentRunRepository).findRunsWithFilters(eq(USER_ID), isNull(), isNull(), eq(SESSION_ID),
                    isNull(), isNull(), isNull(), isNull(), isNull(), any());
        }

        @Test
        @DisplayName("страница и размер доезжают до запроса")
        void pagingPassedThrough() {
            when(agentRunRepository.findRunsWithFilters(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any())).thenReturn(Page.empty());

            service.listRuns(USER_ID, null, null, null, null, null, null, null, 2, 5);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(agentRunRepository).findRunsWithFilters(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), pageable.capture());
            assertEquals(2, pageable.getValue().getPageNumber());
            assertEquals(5, pageable.getValue().getPageSize());
        }
    }

    @Nested
    @DisplayName("Расход токенов")
    class Usage {

        private AgentRunProjection projection(UUID runId) {
            AgentRunProjection p = mock(AgentRunProjection.class);
            lenient().when(p.getId()).thenReturn(runId);
            return p;
        }

        private RunUsageProjection usage(UUID runId, long input, long output, long calls) {
            RunUsageProjection u = mock(RunUsageProjection.class);
            when(u.getRunId()).thenReturn(runId);
            lenient().when(u.getInputTokens()).thenReturn(input);
            lenient().when(u.getOutputTokens()).thenReturn(output);
            lenient().when(u.getCalls()).thenReturn(calls);
            return u;
        }

        @Test
        @DisplayName("расход собирается одним запросом на всю страницу, а не построчно")
        void oneQueryPerPage() {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            // Моки строятся до стабов: вложенный when() внутри thenReturn ломает незавершённое стабание.
            List<AgentRunProjection> page = List.of(projection(first), projection(second));
            List<RunUsageProjection> spend = List.of(usage(first, 1200, 300, 2));
            when(agentRunRepository.findRunsWithFilters(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any())).thenReturn(new PageImpl<>(page));
            when(usageLogRepository.sumByRunIds(List.of(first, second))).thenReturn(spend);

            List<AgentRunResponse> runs =
                    service.listRuns(USER_ID, null, null, null, null, null, null, null, 0, 20).getContent();

            assertEquals(1500, runs.get(0).usage().totalTokens());
            assertEquals(2, runs.get(0).usage().calls());
            // Ран без вызовов модели — нули, а не null: клиенту одна форма на оба случая.
            assertEquals(0, runs.get(1).usage().totalTokens());
            verify(usageLogRepository).sumByRunIds(List.of(first, second));
        }

        @Test
        @DisplayName("кэш-счётчики не входят в total — провайдеры выставляют их отдельной строкой")
        void cacheTokensStayOutOfTotal() {
            RunUsageProjection u = mock(RunUsageProjection.class);
            when(u.getInputTokens()).thenReturn(100L);
            when(u.getOutputTokens()).thenReturn(50L);
            when(u.getCacheReadTokens()).thenReturn(900L);

            RunUsageResponse response = RunUsageResponse.from(u);

            assertEquals(150, response.totalTokens());
            assertEquals(900, response.cacheReadTokens());
        }

        @Test
        @DisplayName("пустая страница — за расходом не идём вовсе")
        void emptyPageSkipsTheQuery() {
            when(agentRunRepository.findRunsWithFilters(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any())).thenReturn(Page.empty());

            service.listRuns(USER_ID, null, null, null, null, null, null, null, 0, 20);

            verify(usageLogRepository, never()).sumByRunIds(any());
        }
    }

    @Nested
    @DisplayName("getRun")
    class GetRun {

        @Test
        @DisplayName("читается тем же запросом, что и листинг — только по ключу")
        void narrowsTheListingToAKey() {
            when(agentRunRepository.findRunsWithFilters(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any())).thenReturn(Page.empty());

            assertThrows(NotFoundStatusException.class, () -> service.getRun(RUN_ID, USER_ID));

            verify(agentRunRepository).findRunsWithFilters(eq(USER_ID), eq(RUN_ID), isNull(), isNull(),
                    isNull(), isNull(), isNull(), isNull(), isNull(), any());
        }
    }

    @Nested
    @DisplayName("listTurns")
    class ListTurns {

        @Test
        @DisplayName("ходы отдаются как есть — без потолков, вместе с рассуждением")
        void turnsComeWhole() {
            when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run(USER_ID)));
            AgentRunTurn turn = AgentRunTurn.builder()
                    .runId(RUN_ID).turnIndex(3).role(AgentTurnRole.ASSISTANT).text("ответ")
                    .thinkingText("длинная цепочка рассуждений")
                    .toolCalls(List.of(Map.of("id", "c1", "name", "board.get_tasks")))
                    .model("gpt-5-mini").callId("wf-llm-9")
                    .build();
            when(turnRepository.findByRunIdOrderByTurnIndexDesc(eq(RUN_ID), any()))
                    .thenReturn(new PageImpl<>(List.of(turn)));

            List<AgentRunTurnResponse> turns = service.listTurns(RUN_ID, USER_ID, 0, 50).getContent();

            assertEquals(1, turns.size());
            assertEquals(3, turns.get(0).turnIndex());
            assertEquals("длинная цепочка рассуждений", turns.get(0).thinkingText());
            assertEquals("wf-llm-9", turns.get(0).callId());
        }

        @Test
        @DisplayName("расход хода подтягивается по call_id одним запросом на страницу")
        void spendPerTurn() {
            when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run(USER_ID)));
            AgentRunTurn assistant = AgentRunTurn.builder()
                    .runId(RUN_ID).turnIndex(1).role(AgentTurnRole.ASSISTANT).text("ответ")
                    .callId("wf-llm-9").build();
            AgentRunTurn tool = AgentRunTurn.builder()
                    .runId(RUN_ID).turnIndex(0).role(AgentTurnRole.TOOL).build();
            when(turnRepository.findByRunIdOrderByTurnIndexDesc(eq(RUN_ID), any()))
                    .thenReturn(new PageImpl<>(List.of(assistant, tool)));
            when(usageLogRepository.findByCallIdIn(List.of("wf-llm-9"))).thenReturn(List.of(
                    LlmUsageLog.builder().callId("wf-llm-9").inputTokens(900).outputTokens(120)
                            .cacheReadTokens(600).build()));

            List<AgentRunTurnResponse> turns = service.listTurns(RUN_ID, USER_ID, 0, 50).getContent();

            assertEquals(1020, turns.get(0).usage().totalTokens());
            assertEquals(600, turns.get(0).usage().cacheReadTokens());
            // Ход без вызова модели — null, а не нули: «бесплатно» и «вызова не было» это разные вещи.
            assertNull(turns.get(1).usage());
        }

        @Test
        @DisplayName("вызова модели не было ни на одном ходе — за расходом не идём")
        void noCallsNoUsageQuery() {
            when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run(USER_ID)));
            when(turnRepository.findByRunIdOrderByTurnIndexDesc(eq(RUN_ID), any()))
                    .thenReturn(new PageImpl<>(List.of(AgentRunTurn.builder()
                            .runId(RUN_ID).turnIndex(0).role(AgentTurnRole.USER).text("вопрос").build())));

            service.listTurns(RUN_ID, USER_ID, 0, 50);

            verify(usageLogRepository, never()).findByCallIdIn(any());
        }

        @Test
        @DisplayName("отчёт о расходе потерялся — null, а не нули: это «неизвестно», а не «даром»")
        void lostUsageReportIsNull() {
            when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run(USER_ID)));
            when(turnRepository.findByRunIdOrderByTurnIndexDesc(eq(RUN_ID), any()))
                    .thenReturn(new PageImpl<>(List.of(AgentRunTurn.builder()
                            .runId(RUN_ID).turnIndex(1).role(AgentTurnRole.ASSISTANT).text("ответ")
                            .callId("wf-llm-lost").build())));
            when(usageLogRepository.findByCallIdIn(List.of("wf-llm-lost"))).thenReturn(List.of());

            assertNull(service.listTurns(RUN_ID, USER_ID, 0, 50).getContent().get(0).usage());
        }

        @Test
        @DisplayName("чужой ран не раскрывается — 404, и до журнала дело не доходит")
        void foreignRunReadsAsAbsent() {
            when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run(OTHER_USER)));

            assertThrows(NotFoundStatusException.class, () -> service.listTurns(RUN_ID, USER_ID, 0, 50));
            verify(turnRepository, never()).findByRunIdOrderByTurnIndexDesc(any(), any());
        }
    }

    @Nested
    @DisplayName("getPrompt")
    class GetPrompt {

        @Test
        @DisplayName("снимок отдаётся простыми коллекциями: между Jackson 2 и 3 это нейтральная территория")
        void promptBecomesPlainCollections() {
            AgentRun run = run(USER_ID);
            JsonNode prompt = JsonUtils.toJsonNodeOrNull(
                    "[{\"role\":\"SYSTEM\",\"text\":\"ты помощник\"},{\"role\":\"USER\",\"text\":\"привет\"}]");
            run.setPrompt(prompt);
            when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

            AgentRunPromptResponse response = service.getPrompt(RUN_ID, USER_ID);

            assertEquals(2, response.messages().size());
            assertEquals("ты помощник", response.messages().get(0).get("text"));
        }

        @Test
        @DisplayName("снимка не было — null, а не пустой список: это разные вещи")
        void missingPromptIsNull() {
            when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run(USER_ID)));

            assertNull(service.getPrompt(RUN_ID, USER_ID).messages());
        }

        @Test
        @DisplayName("чужой ран → 404")
        void foreignRunReadsAsAbsent() {
            when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run(OTHER_USER)));

            assertThrows(NotFoundStatusException.class, () -> service.getPrompt(RUN_ID, USER_ID));
        }
    }
}
