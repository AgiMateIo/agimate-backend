package ru.agimate.controlapi.service.session.compaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.database.entities.AgentRunTurn;
import ru.agimate.controlapi.database.enums.AgentTurnRole;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SessionTranscript — разговор текстом для пересказа")
class SessionTranscriptTest {

    private static AgentRunTurn turn(AgentTurnRole role, String text) {
        return AgentRunTurn.builder().role(role).text(text).build();
    }

    @Test
    @DisplayName("реплики, вызов тула и результат — строками; старая сводка не повторяется")
    void rendersARun() {
        AgentRunTurn call = turn(AgentTurnRole.ASSISTANT, "смотрю");
        call.setToolCalls(List.of(Map.of("id", "c1", "name", "board.get_tasks", "argumentsJson", "{\"s\":1}")));
        AgentRunTurn result = turn(AgentTurnRole.TOOL, null);
        result.setToolResults(List.of(Map.of("id", "c1", "name", "board.get_tasks", "outputJson", "[]", "failed", false)));

        String text = SessionTranscript.renderRun(List.of(
                turn(AgentTurnRole.SYSTEM, "прежняя сводка"),
                turn(AgentTurnRole.USER, "что на доске?"),
                call, result,
                turn(AgentTurnRole.ASSISTANT, "пусто")));

        assertEquals("""
                User: что на доске?
                Agent: смотрю
                Agent called: board.get_tasks {"s":1}
                Tool result: board.get_tasks → []
                Agent: пусто""", text);
    }

    @Test
    @DisplayName("бюджет кончился — теряются старые раны, а не свежие, и это видно")
    void dropsOldestFirst() {
        List<List<AgentRunTurn>> runs = List.of(
                List.of(turn(AgentTurnRole.USER, "старое " + "x".repeat(100))),
                List.of(turn(AgentTurnRole.USER, "свежее")));

        String text = SessionTranscript.render(runs, 50);

        assertTrue(text.startsWith(SessionTranscript.OMITTED), text);
        assertTrue(text.endsWith("User: свежее"), text);
        assertFalse(text.contains("старое"), text);
    }
}
