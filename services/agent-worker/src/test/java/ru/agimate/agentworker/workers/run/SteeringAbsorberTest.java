package ru.agimate.agentworker.workers.run;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.agentworker.ClaimSteeringResponse;
import ru.agimate.agentworker.SteeringMessage;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SteeringAbsorber")
class SteeringAbsorberTest {

    @Mock
    private AgentWorkerClient client;

    private SteeringAbsorber absorber() {
        return new SteeringAbsorber(client, new TurnLog(client, "agent-1", "run-1"), "agent-1", "run-1");
    }

    private static ClaimSteeringResponse claim(String... runIds) {
        ClaimSteeringResponse.Builder builder = ClaimSteeringResponse.newBuilder();
        for (String id : runIds) {
            builder.addMessages(SteeringMessage.newBuilder().setRunId(id).setText("текст " + id));
        }
        return builder.build();
    }

    @Test
    @DisplayName("поглощение: модель видит обрамление, журнал — голый текст")
    void framesForTheModelRecordsBare() {
        when(client.claimSteering("agent-1", "run-1")).thenReturn(claim("r1"));

        List<AgentChatMessage> absorbed = absorber().poll();

        assertEquals(1, absorbed.size());
        assertEquals(SteeringAbsorber.STEERED_PREFIX + "\n\nтекст r1", absorbed.get(0).text());
        verify(client).saveTurn(eq("agent-1"), eq("run-1"), anyInt(), any(), eq("текст r1"),
                isNull(), anyList(), anyList(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("повторно привезённый неподтверждённый захват второй раз не поглощается")
    void refetchedUnconfirmedClaimIsNotAbsorbedTwice() {
        when(client.claimSteering("agent-1", "run-1")).thenReturn(claim("r1"));
        // Подтверждение упало → на бэке steered_at пуст, и следующий шов привезёт r1 снова.
        when(client.markSteered(anyString(), anyString(), anyList()))
                .thenThrow(new RuntimeException("grpc down"));
        SteeringAbsorber absorber = absorber();

        assertEquals(1, absorber.poll().size());
        absorber.confirmOnAssistantTurn();
        assertTrue(absorber.poll().isEmpty());

        // В журнал сообщение тоже ушло ровно один раз.
        verify(client, times(1)).saveTurn(anyString(), anyString(), anyInt(), any(), anyString(),
                isNull(), anyList(), anyList(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("подтверждение ретраится на следующем assistant-ходе и после успеха замолкает")
    void confirmationRetriesUntilAcceptedThenStops() {
        when(client.claimSteering("agent-1", "run-1")).thenReturn(claim("r1"));
        when(client.markSteered(anyString(), anyString(), anyList()))
                .thenThrow(new RuntimeException("grpc down"))
                .thenReturn(null);
        SteeringAbsorber absorber = absorber();
        absorber.poll();

        absorber.confirmOnAssistantTurn(); // упало
        absorber.confirmOnAssistantTurn(); // дошло
        absorber.confirmOnAssistantTurn(); // подтверждать больше нечего

        verify(client, times(2)).markSteered(eq("agent-1"), eq("run-1"), eq(List.of("r1")));
    }

    @Test
    @DisplayName("недоступный захват — пустой ответ, ран продолжает без стиринга")
    void claimFailureMeansEmptyPoll() {
        when(client.claimSteering("agent-1", "run-1")).thenThrow(new RuntimeException("grpc down"));
        SteeringAbsorber absorber = absorber();

        assertTrue(absorber.poll().isEmpty());
        absorber.confirmOnAssistantTurn();
        verify(client, never()).markSteered(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("несколько сообщений за один захват — порядок сохранён, подтверждаются вместе")
    void severalMessagesKeepOrder() {
        when(client.claimSteering("agent-1", "run-1")).thenReturn(claim("r1", "r2"));
        lenient().when(client.markSteered(anyString(), anyString(), anyList())).thenReturn(null);
        SteeringAbsorber absorber = absorber();

        List<AgentChatMessage> absorbed = absorber.poll();

        assertEquals(2, absorbed.size());
        assertTrue(absorbed.get(0).text().endsWith("текст r1"));
        assertTrue(absorbed.get(1).text().endsWith("текст r2"));
        absorber.confirmOnAssistantTurn();
        verify(client).markSteered(eq("agent-1"), eq("run-1"), eq(List.of("r1", "r2")));
    }
}
