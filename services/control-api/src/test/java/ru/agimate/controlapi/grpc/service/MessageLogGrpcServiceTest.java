package ru.agimate.controlapi.grpc.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.GetTurnResponse;
import ru.agimate.agentworker.TurnRole;
import ru.agimate.controlapi.database.entities.AgentRunTurn;
import ru.agimate.controlapi.database.enums.AgentTurnRole;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контракт {@code GetTurn}: реплей воркера перечитывает ход отсюда вместо чекпоинта, поэтому
 * аргументы вызовов тулов едут дословно, а {@code thinking} выводится из {@code thinking_text} —
 * от него зависит число durable-шагов строк прогресса.
 */
@DisplayName("MessageLogGrpcService — маппинг AgentRunTurn → GetTurnResponse")
class MessageLogGrpcServiceTest {

    @Test
    @DisplayName("assistant-ход: текст, thinking и вызовы тулов с аргументами без обрезки")
    void assistantTurnVerbatim() {
        String longArgs = "{\"q\":\"" + "x".repeat(10_000) + "\"}";
        AgentRunTurn turn = AgentRunTurn.builder()
                .role(AgentTurnRole.ASSISTANT)
                .text("looking it up")
                .thinkingText("…")
                .toolCalls(List.of(Map.of("id", "c1", "name", "web.search", "argumentsJson", longArgs)))
                .build();

        GetTurnResponse response = MessageLogGrpcService.toProto(turn);

        assertEquals(TurnRole.TURN_ROLE_ASSISTANT, response.getRole());
        assertEquals("looking it up", response.getText());
        assertTrue(response.getThinking());
        assertEquals(1, response.getToolCallsCount());
        assertEquals("c1", response.getToolCalls(0).getId());
        assertEquals("web.search", response.getToolCalls(0).getName());
        assertEquals(longArgs, response.getToolCalls(0).getArgumentsJson());
    }

    @Test
    @DisplayName("ход без рассуждения и без вызовов: thinking=false, пустые списки, текст пустой")
    void plainTurn() {
        AgentRunTurn turn = AgentRunTurn.builder().role(AgentTurnRole.USER).build();

        GetTurnResponse response = MessageLogGrpcService.toProto(turn);

        assertEquals(TurnRole.TURN_ROLE_USER, response.getRole());
        assertFalse(response.getThinking());
        assertEquals("", response.getText());
        assertEquals(0, response.getToolCallsCount());
        assertEquals(0, response.getToolResultsCount());
    }

    @Test
    @DisplayName("tool-ход: результаты с флагом failed")
    void toolTurn() {
        AgentRunTurn turn = AgentRunTurn.builder()
                .role(AgentTurnRole.TOOL)
                .toolResults(List.of(Map.of("id", "c1", "name", "web.search",
                        "outputJson", "{\"error\":\"boom\"}", "failed", true)))
                .build();

        GetTurnResponse response = MessageLogGrpcService.toProto(turn);

        assertEquals(TurnRole.TURN_ROLE_TOOL, response.getRole());
        assertEquals(1, response.getToolResultsCount());
        assertTrue(response.getToolResults(0).getFailed());
        assertEquals("{\"error\":\"boom\"}", response.getToolResults(0).getOutputJson());
    }
}
