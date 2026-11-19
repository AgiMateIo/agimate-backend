package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.DBOS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.RunEvent;
import ru.agimate.agentworker.WorkerProtocol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RunEventInbox — чтение и опустошение топика событий рана")
class RunEventInboxTest {

    @Test
    @DisplayName("recv отдаёт словарь, а не record: событие читается по имени поля")
    void readsTheKindFromAMap() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("kind", RunEvent.STEERABLE);
        payload.put("toolCallId", null);
        payload.put("runId", "run-2");

        assertEquals(RunEvent.STEERABLE, RunEventInbox.kind(payload));
        assertTrue(RunEventInbox.isSteerable(payload));
    }

    @Test
    @DisplayName("чужие виды событий стирингом не считаются")
    void otherKindsAreNotSteering() {
        assertFalse(RunEventInbox.isSteerable(Map.of("kind", RunEvent.TOOL_FINISHED)));
        assertFalse(RunEventInbox.isSteerable(Map.of("kind", RunEvent.CANCEL_REQUESTED)));
    }

    @Test
    @DisplayName("непонятная нагрузка — не событие и не ошибка")
    void unknownPayloadIsNoEvent() {
        assertNull(RunEventInbox.kind("what"));
        assertNull(RunEventInbox.kind(Map.of("type", "steerable")));
        assertFalse(RunEventInbox.isSteerable(null));
    }

    @Test
    @DisplayName("опустошение забирает всё, что лежит, и останавливается на пустом")
    void drainsUntilEmpty() {
        DBOS dbos = mock(DBOS.class);
        when(dbos.recv(eq(WorkerProtocol.RUN_EVENTS_TOPIC), any(Duration.class))).thenReturn(
                Optional.of((Object) Map.of("kind", RunEvent.STEERABLE)),
                Optional.of((Object) Map.of("kind", RunEvent.TOOL_FINISHED)),
                Optional.empty());

        assertEquals(2, RunEventInbox.drain(dbos));
        verify(dbos, times(3)).recv(eq(WorkerProtocol.RUN_EVENTS_TOPIC), any(Duration.class));
    }

    @Test
    @DisplayName("пустой топик — ноль и один короткий взгляд, а не ожидание")
    void emptyInboxDrainsNothing() {
        DBOS dbos = mock(DBOS.class);

        assertEquals(0, RunEventInbox.drain(dbos));
        verify(dbos).recv(eq(WorkerProtocol.RUN_EVENTS_TOPIC), any(Duration.class));
    }

    @Test
    @DisplayName("шторм ограничен потолком: остаток заберёт следующее ожидание")
    void drainIsCapped() {
        DBOS dbos = mock(DBOS.class);
        // Always something to read: without a cap the loop would never end.
        when(dbos.recv(eq(WorkerProtocol.RUN_EVENTS_TOPIC), any(Duration.class)))
                .thenReturn(Optional.of((Object) Map.of("kind", RunEvent.STEERABLE)));

        assertEquals(64, RunEventInbox.drain(dbos));
    }

    @Test
    @DisplayName("взгляд не ждёт: таймаут опустошения — единицы миллисекунд")
    void peekDoesNotBlock() {
        DBOS dbos = mock(DBOS.class);
        List<Duration> timeouts = new ArrayList<>();
        when(dbos.recv(eq(WorkerProtocol.RUN_EVENTS_TOPIC), any(Duration.class))).thenAnswer(inv -> {
            timeouts.add(inv.getArgument(1));
            return Optional.empty();
        });

        RunEventInbox.drain(dbos);

        assertEquals(1, timeouts.size());
        assertTrue(timeouts.get(0).toMillis() <= 50, "peek timeout: " + timeouts.get(0));
    }
}
