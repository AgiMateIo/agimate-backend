package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.json.DBOSJavaSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.RunEvent;
import ru.agimate.agentworker.WorkerProtocol;

import java.time.Duration;
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

    /**
     * The one test that would have caught the bug: the payload is what the DBOS serializer actually
     * produces and consumes, not what we would like it to look like.
     */
    @Test
    @DisplayName("payload из сериализатора DBOS возвращается записью, и стиринг в нём читается")
    void roundTripThroughTheRealSerializer() {
        Object payload = DBOSJavaSerializer.INSTANCE.deserialize(
                DBOSJavaSerializer.INSTANCE.serialize(RunEvent.steerable("run-2")));

        assertEquals(RunEvent.STEERABLE, RunEventInbox.kind(payload));
        assertTrue(RunEventInbox.isSteerable(payload));
    }

    @Test
    @DisplayName("запись разбирается и напрямую: вид события и стиринг")
    void readsTheRecord() {
        assertTrue(RunEventInbox.isSteerable(RunEvent.steerable("run-2")));
        assertEquals(RunEvent.TOOL_FINISHED, RunEventInbox.kind(RunEvent.toolFinished("call-1")));
        assertFalse(RunEventInbox.isSteerable(RunEvent.cancelRequested()));
    }

    @Test
    @DisplayName("чужие виды событий стирингом не считаются")
    void otherKindsAreNotSteering() {
        assertFalse(RunEventInbox.isSteerable(RunEvent.toolFinished("call-1")));
        assertFalse(RunEventInbox.isSteerable(RunEvent.cancelRequested()));
    }

    @Test
    @DisplayName("словарь — запасной путь для сериализатора без type id; прочее — не событие")
    void mapIsTheFallback() {
        assertEquals(RunEvent.STEERABLE, RunEventInbox.kind(Map.of("kind", RunEvent.STEERABLE)));
        assertNull(RunEventInbox.kind("what"));
        assertNull(RunEventInbox.kind(Map.of("type", "steerable")));
        assertFalse(RunEventInbox.isSteerable(null));
    }

    @Test
    @DisplayName("опустошение забирает всё, что лежит, и останавливается на пустом")
    void drainsUntilEmpty() {
        DBOS dbos = mock(DBOS.class);
        when(dbos.recv(eq(WorkerProtocol.RUN_EVENTS_TOPIC), any(Duration.class))).thenReturn(
                Optional.of((Object) RunEvent.steerable("run-2")),
                Optional.of((Object) RunEvent.toolFinished("call-1")),
                Optional.empty());

        RunEventInbox.Drained drained = RunEventInbox.drain(dbos);

        assertEquals(2, drained.count());
        verify(dbos, times(3)).recv(eq(WorkerProtocol.RUN_EVENTS_TOPIC), any(Duration.class));
    }

    @Test
    @DisplayName("стиринг за другим сообщением не теряется: опустошение о нём сообщает")
    void drainReportsASteerableBehindAnotherMessage() {
        DBOS dbos = mock(DBOS.class);
        when(dbos.recv(eq(WorkerProtocol.RUN_EVENTS_TOPIC), any(Duration.class))).thenReturn(
                Optional.of((Object) RunEvent.toolFinished("call-1")),
                Optional.of((Object) RunEvent.steerable("run-2")),
                Optional.empty());

        assertTrue(RunEventInbox.drain(dbos).steerable());
    }

    @Test
    @DisplayName("пустой топик — ноль и один короткий взгляд, а не ожидание")
    void emptyInboxDrainsNothing() {
        DBOS dbos = mock(DBOS.class);

        RunEventInbox.Drained drained = RunEventInbox.drain(dbos);

        assertEquals(0, drained.count());
        assertFalse(drained.steerable());
        verify(dbos).recv(eq(WorkerProtocol.RUN_EVENTS_TOPIC), any(Duration.class));
    }

    @Test
    @DisplayName("шторм ограничен потолком: остаток заберёт следующее ожидание")
    void drainIsCapped() {
        DBOS dbos = mock(DBOS.class);
        // Always something to read: without a cap the loop would never end.
        when(dbos.recv(eq(WorkerProtocol.RUN_EVENTS_TOPIC), any(Duration.class)))
                .thenReturn(Optional.of((Object) RunEvent.steerable("run-2")));

        assertEquals(64, RunEventInbox.drain(dbos).count());
    }

    @Test
    @DisplayName("взгляд не ждёт: таймаут опустошения — единицы миллисекунд")
    void peekDoesNotBlock() {
        DBOS dbos = mock(DBOS.class);
        List<Duration> timeouts = new java.util.ArrayList<>();
        when(dbos.recv(eq(WorkerProtocol.RUN_EVENTS_TOPIC), any(Duration.class))).thenAnswer(inv -> {
            timeouts.add(inv.getArgument(1));
            return Optional.empty();
        });

        RunEventInbox.drain(dbos);

        assertEquals(1, timeouts.size());
        assertTrue(timeouts.get(0).toMillis() <= 50, "peek timeout: " + timeouts.get(0));
    }
}
