package ru.agimate.controlapi.service.runcontext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.entities.AgentRunTurn;
import ru.agimate.controlapi.database.enums.AgentTurnRole;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentRunTurnRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunHistoryAssembler — история из журнала ходов")
class RunHistoryAssemblerTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final Set<ContextSpec.HistoryPart> ALL_PARTS =
            Set.of(ContextSpec.HistoryPart.DIALOG, ContextSpec.HistoryPart.TOOLS);

    @Mock private AgentRunRepository agentRunRepository;
    @Mock private AgentRunTurnRepository turnRepository;

    private RunHistoryAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new RunHistoryAssembler(agentRunRepository, turnRepository);
    }

    /** The window query returns newest first; the ledger of each run is ordered by turn_index. */
    private void stubRuns(List<UUID> newestFirst, List<AgentRunTurn> turns) {
        when(agentRunRepository.findHistoryRunIds(eq(SESSION_ID), any())).thenReturn(List.copyOf(newestFirst));
        when(turnRepository.findByRunIdInOrderByRunIdAscTurnIndexAsc(anyList())).thenReturn(turns);
    }

    private static AgentRunTurn turn(UUID runId, int index, AgentTurnRole role, String text) {
        return AgentRunTurn.builder()
                .runId(runId).sessionId(SESSION_ID).agentId(UUID.randomUUID())
                .turnIndex(index).role(role).text(text)
                .build();
    }

    private static AgentRunTurn callTurn(UUID runId, int index, String preamble, String name) {
        AgentRunTurn t = turn(runId, index, AgentTurnRole.ASSISTANT, preamble);
        t.setToolCalls(List.of(Map.of("id", "c1", "name", name, "argumentsJson", "{}")));
        return t;
    }

    private static AgentRunTurn resultTurn(UUID runId, int index, String output) {
        AgentRunTurn t = turn(runId, index, AgentTurnRole.TOOL, null);
        t.setToolResults(List.of(Map.of("id", "c1", "name", "board.get_tasks",
                "outputJson", output, "failed", false)));
        return t;
    }

    @Nested
    @DisplayName("Раскладка ходов")
    class Layout {

        @Test
        @DisplayName("раны идут в хронологии, внутри рана — по turn_index")
        void chronologicalOrder() {
            UUID older = UUID.randomUUID();
            UUID newer = UUID.randomUUID();
            stubRuns(List.of(newer, older), List.of(
                    turn(older, 0, AgentTurnRole.USER, "первый вопрос"),
                    turn(older, 1, AgentTurnRole.ASSISTANT, "первый ответ"),
                    turn(newer, 0, AgentTurnRole.USER, "второй вопрос"),
                    turn(newer, 1, AgentTurnRole.ASSISTANT, "второй ответ")));

            List<RunHistoryMessage> history = assembler.assemble(SESSION_ID, 20, ALL_PARTS);

            assertEquals(List.of(
                    new RunHistoryMessage(ChannelSessionMessageKind.INBOUND, "первый вопрос"),
                    new RunHistoryMessage(ChannelSessionMessageKind.ANSWER, "первый ответ"),
                    new RunHistoryMessage(ChannelSessionMessageKind.INBOUND, "второй вопрос"),
                    new RunHistoryMessage(ChannelSessionMessageKind.ANSWER, "второй ответ")),
                    history);
        }

        @Test
        @DisplayName("тул-ход уезжает двумя соседними записями: вызовы, следом результаты")
        void toolTurnAsAdjacentPair() {
            UUID runId = UUID.randomUUID();
            stubRuns(List.of(runId), List.of(
                    turn(runId, 0, AgentTurnRole.USER, "что на доске?"),
                    callTurn(runId, 1, "смотрю доску", "board.get_tasks"),
                    resultTurn(runId, 2, "{\"tasks\":[]}"),
                    turn(runId, 3, AgentTurnRole.ASSISTANT, "доска пуста")));

            List<RunHistoryMessage> history = assembler.assemble(SESSION_ID, 20, ALL_PARTS);

            assertEquals(4, history.size());
            RunHistoryMessage calls = history.get(1);
            assertEquals("смотрю доску", calls.toolTurn().text());
            assertEquals("board.get_tasks", calls.toolTurn().calls().get(0).name());
            assertTrue(calls.toolTurn().results().isEmpty());
            RunHistoryMessage results = history.get(2);
            assertTrue(results.toolTurn().calls().isEmpty());
            assertEquals("{\"tasks\":[]}", results.toolTurn().results().get(0).outputJson());
        }

        @Test
        @DisplayName("пустой текстовый ход не занимает места в контексте")
        void blankTurnsSkipped() {
            UUID runId = UUID.randomUUID();
            stubRuns(List.of(runId), List.of(
                    turn(runId, 0, AgentTurnRole.USER, "вопрос"),
                    turn(runId, 1, AgentTurnRole.ASSISTANT, "  ")));

            assertEquals(1, assembler.assemble(SESSION_ID, 20, ALL_PARTS).size());
        }
    }

    @Nested
    @DisplayName("Части истории")
    class Parts {

        @Test
        @DisplayName("без TOOLS выпадают обе половины хода — осиротевших результатов не остаётся")
        void withoutToolsBothHalvesGo() {
            UUID runId = UUID.randomUUID();
            stubRuns(List.of(runId), List.of(
                    turn(runId, 0, AgentTurnRole.USER, "что на доске?"),
                    callTurn(runId, 1, "смотрю доску", "board.get_tasks"),
                    resultTurn(runId, 2, "{\"tasks\":[]}"),
                    turn(runId, 3, AgentTurnRole.ASSISTANT, "доска пуста")));

            List<RunHistoryMessage> history =
                    assembler.assemble(SESSION_ID, 20, Set.of(ContextSpec.HistoryPart.DIALOG));

            assertEquals(List.of(
                    new RunHistoryMessage(ChannelSessionMessageKind.INBOUND, "что на доске?"),
                    new RunHistoryMessage(ChannelSessionMessageKind.ANSWER, "доска пуста")),
                    history);
        }

        @Test
        @DisplayName("без DIALOG остаются только тул-ходы")
        void withoutDialogOnlyTools() {
            UUID runId = UUID.randomUUID();
            stubRuns(List.of(runId), List.of(
                    turn(runId, 0, AgentTurnRole.USER, "что на доске?"),
                    callTurn(runId, 1, "смотрю доску", "board.get_tasks"),
                    resultTurn(runId, 2, "{\"tasks\":[]}"),
                    turn(runId, 3, AgentTurnRole.ASSISTANT, "доска пуста")));

            List<RunHistoryMessage> history =
                    assembler.assemble(SESSION_ID, 20, Set.of(ContextSpec.HistoryPart.TOOLS));

            assertEquals(2, history.size());
            assertTrue(history.stream().allMatch(m -> m.toolTurn() != null));
        }

        @Test
        @DisplayName("рассуждения не уезжают в контекст ни при каком наборе частей")
        void reasoningNeverTravels() {
            UUID runId = UUID.randomUUID();
            AgentRunTurn thinking = turn(runId, 1, AgentTurnRole.ASSISTANT, "ответ");
            thinking.setThinking(true);
            thinking.setThinkingText("длинная цепочка рассуждений");
            stubRuns(List.of(runId), List.of(turn(runId, 0, AgentTurnRole.USER, "вопрос"), thinking));

            List<RunHistoryMessage> history = assembler.assemble(SESSION_ID, 20,
                    Set.of(ContextSpec.HistoryPart.DIALOG, ContextSpec.HistoryPart.TOOLS,
                            ContextSpec.HistoryPart.REASONING));

            assertTrue(history.stream().noneMatch(m -> m.text().contains("рассуждений")));
        }
    }

    @Nested
    @DisplayName("Потолки")
    class Caps {

        @Test
        @DisplayName("гигантский вывод тула режется до контекстного бюджета")
        void hugeOutputCapped() {
            UUID runId = UUID.randomUUID();
            String huge = "x".repeat(RunHistoryAssembler.TOOL_JSON_CONTEXT_CAP + 100);
            stubRuns(List.of(runId), List.of(
                    callTurn(runId, 0, "смотрю", "board.get_tasks"),
                    resultTurn(runId, 1, huge)));

            List<RunHistoryMessage> history = assembler.assemble(SESSION_ID, 20, ALL_PARTS);

            String output = history.get(1).toolTurn().results().get(0).outputJson();
            assertTrue(output.endsWith("…[truncated]"));
            assertTrue(output.length() < huge.length());
        }

        @Test
        @DisplayName("бюджет ходов отбрасывает раны целиком, начиная со старых")
        void turnBudgetDropsWholeRuns() {
            UUID older = UUID.randomUUID();
            UUID newer = UUID.randomUUID();
            List<AgentRunTurn> turns = new ArrayList<>();
            for (int i = 0; i < RunHistoryAssembler.MAX_HISTORY_TURNS; i++) {
                turns.add(turn(older, i, AgentTurnRole.ASSISTANT, "старый ход " + i));
            }
            turns.add(turn(newer, 0, AgentTurnRole.USER, "свежий вопрос"));
            stubRuns(List.of(newer, older), turns);

            List<RunHistoryMessage> history = assembler.assemble(SESSION_ID, 20, ALL_PARTS);

            assertEquals(List.of(new RunHistoryMessage(ChannelSessionMessageKind.INBOUND, "свежий вопрос")),
                    history);
        }
    }

    @Nested
    @DisplayName("Пустые случаи")
    class Empty {

        @Test
        @DisplayName("прямой ран без сессии — истории нет и запросов нет")
        void noSession() {
            assertTrue(assembler.assemble(null, 20, ALL_PARTS).isEmpty());
            verifyNoInteractions(agentRunRepository, turnRepository);
        }

        @Test
        @DisplayName("окно 0 — журнал не читается вовсе")
        void zeroWindow() {
            assertTrue(assembler.assemble(SESSION_ID, 0, ALL_PARTS).isEmpty());
            verifyNoInteractions(agentRunRepository, turnRepository);
        }

        @Test
        @DisplayName("подходящих ранов нет — за ходами не идём")
        void noEligibleRuns() {
            when(agentRunRepository.findHistoryRunIds(eq(SESSION_ID), any())).thenReturn(List.of());

            assertTrue(assembler.assemble(SESSION_ID, 20, ALL_PARTS).isEmpty());
            verify(turnRepository, never()).findByRunIdInOrderByRunIdAscTurnIndexAsc(anyList());
        }
    }
}
