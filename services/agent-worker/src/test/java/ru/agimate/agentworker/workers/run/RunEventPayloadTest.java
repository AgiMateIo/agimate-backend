package ru.agimate.agentworker.workers.run;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.RunEvent;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RunEventPayload — разбор полезной нагрузки DBOS-сообщения")
class RunEventPayloadTest {

    @Test
    @DisplayName("recv отдаёт словарь, а не record: событие читается по имени поля")
    void readsTheKindFromAMap() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("kind", RunEvent.STEERABLE);
        payload.put("toolCallId", null);
        payload.put("runId", "run-2");

        assertEquals(RunEvent.STEERABLE, RunEventPayload.kind(payload));
        assertTrue(RunEventPayload.isSteerable(payload));
    }

    @Test
    @DisplayName("чужие виды событий стирингом не считаются")
    void otherKindsAreNotSteering() {
        assertFalse(RunEventPayload.isSteerable(Map.of("kind", RunEvent.TOOL_FINISHED)));
        assertFalse(RunEventPayload.isSteerable(Map.of("kind", RunEvent.CANCEL_REQUESTED)));
    }

    @Test
    @DisplayName("непонятная нагрузка — не событие и не ошибка")
    void unknownPayloadIsNoEvent() {
        assertNull(RunEventPayload.kind("what"));
        assertNull(RunEventPayload.kind(Map.of("type", "steerable")));
        assertFalse(RunEventPayload.isSteerable(null));
    }
}
