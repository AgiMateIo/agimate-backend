package ru.agimate.controlapi.service.trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Trigger")
class TriggerTest {

    @Nested
    @DisplayName("fromSource — внешний источник со своим id/временем")
    class FromSource {

        @Test
        @DisplayName("проносит id и occurredAt источника без изменений")
        void passesThroughSourceIdentity() {
            Instant occurredAt = Instant.parse("2026-07-01T10:15:30Z");

            Trigger trigger = Trigger.fromSource(
                    "webchat", "conn-1", "message.received", "evt-42",
                    Map.of("text", "hi"), occurredAt);

            assertEquals("webchat", trigger.connectorCode());
            assertEquals("conn-1", trigger.connectionId());
            assertEquals("message.received", trigger.name());
            assertEquals("evt-42", trigger.id());
            assertEquals(occurredAt.toString(), trigger.occurredAt());
            assertEquals(Map.of("text", "hi"), trigger.data());
            assertNull(trigger.context());
        }

        @Test
        @DisplayName("генерирует случайный UUID, когда id пустой или null")
        void fallsBackToRandomIdWhenAbsent() {
            Trigger blank = Trigger.fromSource("c", "conn", "n", "  ", Map.of(), Instant.now());
            Trigger nullId = Trigger.fromSource("c", "conn", "n", null, Map.of(), Instant.now());

            assertDoesNotThrow(() -> UUID.fromString(blank.id()));
            assertDoesNotThrow(() -> UUID.fromString(nullId.id()));
        }

        @Test
        @DisplayName("подставляет текущее время, когда occurredAt не задан")
        void fallsBackToNowWhenOccurredAtAbsent() {
            Trigger trigger = Trigger.fromSource("c", "conn", "n", "id", Map.of(), null);

            assertNotNull(trigger.occurredAt());
            assertDoesNotThrow(() -> Instant.parse(trigger.occurredAt()));
        }
    }
}
