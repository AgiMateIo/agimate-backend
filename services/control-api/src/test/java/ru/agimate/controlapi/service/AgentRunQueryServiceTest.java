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
import ru.agimate.controlapi.controller.manage.dto.AgentRunTurnResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.AgentRunTurn;
import ru.agimate.controlapi.database.enums.AgentTurnRole;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentRunTurnRepository;

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

    private AgentRunQueryService service;

    @BeforeEach
    void setUp() {
        service = new AgentRunQueryService(agentRunRepository, turnRepository);
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
                    any(), any())).thenReturn(Page.empty());

            service.listRuns(USER_ID, null, SESSION_ID, null, "  ", "", "  ", null, 0, 20);

            verify(agentRunRepository).findRunsWithFilters(eq(USER_ID), isNull(), eq(SESSION_ID),
                    isNull(), isNull(), isNull(), isNull(), isNull(), any());
        }

        @Test
        @DisplayName("страница и размер доезжают до запроса")
        void pagingPassedThrough() {
            when(agentRunRepository.findRunsWithFilters(any(), any(), any(), any(), any(), any(), any(),
                    any(), any())).thenReturn(Page.empty());

            service.listRuns(USER_ID, null, null, null, null, null, null, null, 2, 5);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(agentRunRepository).findRunsWithFilters(any(), any(), any(), any(), any(), any(),
                    any(), any(), pageable.capture());
            assertEquals(2, pageable.getValue().getPageNumber());
            assertEquals(5, pageable.getValue().getPageSize());
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
            JsonNode prompt = JsonUtils.toJsonNode(
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
