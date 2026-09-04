package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.AgentRunTurn;
import ru.agimate.controlapi.database.enums.AgentTurnRole;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentRunTurnRepository;
import ru.agimate.controlapi.service.dto.ToolTurnRecord;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentRunTurnService")
class AgentRunTurnServiceTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock
    private AgentRunRepository agentRunRepository;
    @Mock
    private AgentRunTurnRepository turnRepository;

    @InjectMocks
    private AgentRunTurnService service;

    private AgentRun run(UUID ownerAgentId, UUID sessionId) {
        AgentRun run = mock(AgentRun.class);
        Agent agent = mock(Agent.class);
        when(run.getAgent()).thenReturn(agent);
        when(agent.getId()).thenReturn(ownerAgentId);
        // sessionId читается только на успешном пути (после проверки владельца) — lenient.
        lenient().when(run.getSessionId()).thenReturn(sessionId);
        return run;
    }

    @Nested
    @DisplayName("assistant-ход")
    class AssistantTurn {

        @Test
        @DisplayName("пишет role/text/thinking + tool_calls (json), результаты — null; session_id из рана")
        void writesAssistantWithToolCalls() {
            AgentRun run = run(AGENT_ID, SESSION_ID);
            when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
            when(turnRepository.insertIgnoreConflict(eq(RUN_ID), eq(SESSION_ID), eq(AGENT_ID), eq(0),
                    eq("ASSISTANT"), eq("preamble"), eq("сначала посмотрю погоду"),
                    argThat(j -> j != null && j.contains("weather")),
                    isNull(), isNull(), isNull(), isNull())).thenReturn(1);

            AgentRunTurnService.SaveResult result = service.save(AGENT_ID, RUN_ID, 0, AgentTurnRole.ASSISTANT,
                    "preamble", "сначала посмотрю погоду",
                    List.of(new ToolTurnRecord.Call("c1", "weather", "{\"city\":\"Berlin\"}")),
                    List.of(), null, null, null);

            assertFalse(result.duplicate());
        }

        @Test
        @DisplayName("пустой текст → null; пустой список вызовов → null json")
        void emptyTextAndCallsBecomeNull() {
            AgentRun run = run(AGENT_ID, null);
            when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
            when(turnRepository.insertIgnoreConflict(eq(RUN_ID), isNull(), eq(AGENT_ID), eq(0),
                    eq("ASSISTANT"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                    isNull())).thenReturn(1);

            service.save(AGENT_ID, RUN_ID, 0, AgentTurnRole.ASSISTANT, "", "",
                    List.of(), List.of(), null, null, null);

            // пустое рассуждение — это «модель не рассуждала», а не пустая строка в колонке
            verify(turnRepository).insertIgnoreConflict(eq(RUN_ID), isNull(), eq(AGENT_ID), eq(0),
                    eq("ASSISTANT"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                    isNull());
        }
    }

    @Test
    @DisplayName("tool-ход: role TOOL + tool_results (json), вызовы — null")
    void writesToolResults() {
        AgentRun run = run(AGENT_ID, SESSION_ID);
        when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(turnRepository.insertIgnoreConflict(eq(RUN_ID), eq(SESSION_ID), eq(AGENT_ID), eq(1),
                eq("TOOL"), isNull(), isNull(), isNull(),
                argThat(j -> j != null && j.contains("sunny")), isNull(), isNull(), isNull())).thenReturn(1);

        service.save(AGENT_ID, RUN_ID, 1, AgentTurnRole.TOOL, null, null, List.of(),
                List.of(new ToolTurnRecord.Result("c1", "weather", "{\"sky\":\"sunny\"}", false)),
                null, null, null);

        verify(turnRepository).insertIgnoreConflict(eq(RUN_ID), eq(SESSION_ID), eq(AGENT_ID), eq(1),
                eq("TOOL"), isNull(), isNull(), isNull(),
                argThat(j -> j != null && j.contains("sunny")), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("insert вернул 0 (конфликт) → duplicate=true")
    void duplicateOnConflict() {
        AgentRun run = run(AGENT_ID, SESSION_ID);
        when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(turnRepository.insertIgnoreConflict(eq(RUN_ID), eq(SESSION_ID), eq(AGENT_ID), eq(0),
                eq("ASSISTANT"), eq("hi"), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull())).thenReturn(0);

        AgentRunTurnService.SaveResult result = service.save(AGENT_ID, RUN_ID, 0, AgentTurnRole.ASSISTANT,
                "hi", null, List.of(), List.of(), null, null, null);

        assertTrue(result.duplicate());
    }

    @Test
    @DisplayName("ран не найден → NotFoundStatusException, запись не трогается")
    void runNotFound() {
        when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.empty());

        assertThrows(NotFoundStatusException.class, () -> service.save(AGENT_ID, RUN_ID, 0,
                AgentTurnRole.ASSISTANT, "hi", null, List.of(), List.of(), null, null, null));
        verifyNoInteractions(turnRepository);
    }

    @Test
    @DisplayName("ран принадлежит другому агенту → BadRequestStatusException")
    void agentMismatch() {
        AgentRun run = run(UUID.randomUUID(), SESSION_ID);
        when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        assertThrows(BadRequestStatusException.class, () -> service.save(AGENT_ID, RUN_ID, 0,
                AgentTurnRole.ASSISTANT, "hi", null, List.of(), List.of(), null, null, null));
        verifyNoInteractions(turnRepository);
    }

    @Nested
    @DisplayName("isLedgerIntact — проверка журнала при завершении рана")
    class LedgerIntact {

        private void stubLast(AgentTurnRole role, int index, List<Map<String, Object>> calls) {
            AgentRunTurn last = AgentRunTurn.builder()
                    .runId(RUN_ID).turnIndex(index).role(role).toolCalls(calls).build();
            when(turnRepository.findFirstByRunIdOrderByTurnIndexDesc(RUN_ID)).thenReturn(Optional.of(last));
        }

        @Test
        @DisplayName("непрерывный журнал, закрытый ответом — годен")
        void contiguousLedger() {
            stubLast(AgentTurnRole.ASSISTANT, 3, null);
            when(turnRepository.countByRunId(RUN_ID)).thenReturn(4L);

            assertTrue(service.isLedgerIntact(RUN_ID));
        }

        @Test
        @DisplayName("дыра видна арифметикой: ходов меньше, чем индекс последнего")
        void gapInLedger() {
            stubLast(AgentTurnRole.ASSISTANT, 5, null);
            when(turnRepository.countByRunId(RUN_ID)).thenReturn(4L);

            assertFalse(service.isLedgerIntact(RUN_ID));
        }

        @Test
        @DisplayName("последний ход — неотвеченный вызов тула: пара разорвана, журнал непригоден")
        void unansweredTailCall() {
            stubLast(AgentTurnRole.ASSISTANT, 2, List.of(Map.of("id", "c1", "name", "t")));
            when(turnRepository.countByRunId(RUN_ID)).thenReturn(3L);

            assertFalse(service.isLedgerIntact(RUN_ID));
        }

        @Test
        @DisplayName("журнала нет вовсе — воспроизводить нечего, но и ломать нечего")
        void emptyLedger() {
            when(turnRepository.findFirstByRunIdOrderByTurnIndexDesc(RUN_ID)).thenReturn(Optional.empty());

            assertTrue(service.isLedgerIntact(RUN_ID));
            verify(turnRepository, never()).countByRunId(RUN_ID);
        }
    }

    @Nested
    @DisplayName("get — ход по ключу журнала (GetTurn)")
    class GetTurn {

        @Test
        @DisplayName("ход агента возвращается как есть")
        void returnsOwnTurn() {
            AgentRunTurn turn = AgentRunTurn.builder().agentId(AGENT_ID).runId(RUN_ID).turnIndex(3).build();
            when(turnRepository.findByRunIdAndTurnIndex(RUN_ID, 3)).thenReturn(Optional.of(turn));

            assertSame(turn, service.get(AGENT_ID, RUN_ID, 3));
        }

        @Test
        @DisplayName("нет хода → NotFoundStatusException")
        void notFound() {
            when(turnRepository.findByRunIdAndTurnIndex(RUN_ID, 3)).thenReturn(Optional.empty());

            assertThrows(NotFoundStatusException.class, () -> service.get(AGENT_ID, RUN_ID, 3));
        }

        @Test
        @DisplayName("ход чужого агента → BadRequestStatusException")
        void foreignAgent() {
            AgentRunTurn turn = AgentRunTurn.builder().agentId(UUID.randomUUID()).runId(RUN_ID).turnIndex(3).build();
            when(turnRepository.findByRunIdAndTurnIndex(RUN_ID, 3)).thenReturn(Optional.of(turn));

            assertThrows(BadRequestStatusException.class, () -> service.get(AGENT_ID, RUN_ID, 3));
        }
    }
}
