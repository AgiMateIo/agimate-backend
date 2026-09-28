package ru.agimate.controlapi.connectors.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AgentTimestamps")
class AgentTimestampsTest {

    private static final LocalDateTime STORED = LocalDateTime.of(2026, 9, 28, 14, 40, 0, 123_000_000);

    @Nested
    @DisplayName("формат")
    class Format {

        @Test
        @DisplayName("хранимое UTC-время в поясе — со смещением и именем зоны, без долей секунды")
        void zoned() {
            assertEquals("2026-09-28T17:40:00+03:00[Europe/Moscow]",
                    AgentTimestamps.format(STORED, ZoneId.of("Europe/Moscow")));
        }

        @Test
        @DisplayName("UTC пишется как Z с именем зоны")
        void utc() {
            assertEquals("2026-09-28T14:40:00Z[UTC]", AgentTimestamps.utc(STORED));
        }

        @Test
        @DisplayName("null остаётся null")
        void nullStaysNull() {
            assertNull(AgentTimestamps.utc(null));
        }

        @Test
        @DisplayName("DTO в карту: LocalDateTime становится UTC-строкой с зоной")
        void toMap() {
            record Card(String title, LocalDateTime createdAt) {}

            Map<String, Object> map = AgentTimestamps.toMap(Map.of("task", new Card("t", STORED)));

            assertEquals(Map.of("task", Map.of("title", "t", "createdAt", "2026-09-28T14:40:00Z[UTC]")), map);
        }
    }

    @Nested
    @DisplayName("разбор")
    class Parse {

        @Test
        @DisplayName("без смещения — UTC")
        void localIsUtc() {
            assertEquals(LocalDateTime.of(2026, 9, 1, 10, 0), AgentTimestamps.parseUtc("2026-09-01T10:00:00", "since"));
        }

        @Test
        @DisplayName("смещение и зона переводятся в UTC")
        void offsetAndZone() {
            LocalDateTime expected = LocalDateTime.of(2026, 9, 1, 10, 0);
            assertEquals(expected, AgentTimestamps.parseUtc("2026-09-01T13:00:00+03:00", "since"));
            assertEquals(expected, AgentTimestamps.parseUtc("2026-09-01T10:00:00Z", "since"));
            assertEquals(expected, AgentTimestamps.parseUtc("2026-09-01T13:00:00+03:00[Europe/Moscow]", "since"));
        }

        @Test
        @DisplayName("пусто — null, мусор — ошибка для агента")
        void blankAndGarbage() {
            assertNull(AgentTimestamps.parseUtc(" ", "since"));
            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> AgentTimestamps.parseUtc("yesterday", "since"));
            assertTrue(e.getMessage().startsWith("Invalid since: 'yesterday'"));
        }
    }
}
