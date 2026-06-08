package ru.agimate.controlapi.service.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InputFilterEvaluatorTest {

    @Nested
    @DisplayName("matches()")
    class MatchesTests {

        @Test
        @DisplayName("null and empty filters match anything")
        void emptyFilter_matchesAlways() {
            assertTrue(InputFilterEvaluator.matches(null, Map.of("a", 1)));
            assertTrue(InputFilterEvaluator.matches(Map.of(), Map.of("a", 1)));
        }

        @Test
        @DisplayName("filter matches scalar at top level")
        void topLevelScalar_matches() {
            Map<String, Object> filter = Map.of("type", "ping");
            assertTrue(InputFilterEvaluator.matches(filter, Map.of("type", "ping", "x", 1)));
            assertFalse(InputFilterEvaluator.matches(filter, Map.of("type", "pong")));
        }

        @Test
        @DisplayName("filter resolves nested dot-path")
        void dotPath_resolvesNested() {
            Map<String, Object> filter = Map.of("data.message.chat_id", 12345);
            Map<String, Object> data = Map.of("data", Map.of("message", Map.of("chat_id", 12345, "text", "hi")));
            assertTrue(InputFilterEvaluator.matches(filter, data));
        }

        @Test
        @DisplayName("filter fails when nested path missing")
        void missingPath_fails() {
            Map<String, Object> filter = Map.of("data.message.chat_id", 12345);
            assertFalse(InputFilterEvaluator.matches(filter, Map.of("data", Map.of("message", Map.of("text", "hi")))));
        }

        @Test
        @DisplayName("compares numbers loosely (Long vs Integer)")
        void numberLooseEquality() {
            Map<String, Object> filter = Map.of("n", 100);
            assertTrue(InputFilterEvaluator.matches(filter, Map.of("n", 100L)));
        }

        @Test
        @DisplayName("AND-conjunction across multiple keys")
        void andConjunction() {
            Map<String, Object> filter = Map.of(
                    "data.message.chat_id", 1L,
                    "data.message.kind", "text"
            );
            Map<String, Object> data = Map.of("data", Map.of("message", Map.of("chat_id", 1L, "kind", "text")));
            assertTrue(InputFilterEvaluator.matches(filter, data));

            Map<String, Object> data2 = Map.of("data", Map.of("message", Map.of("chat_id", 1L, "kind", "photo")));
            assertFalse(InputFilterEvaluator.matches(filter, data2));
        }
    }

    @Nested
    @DisplayName("resolvePath()")
    class ResolvePathTests {

        @Test
        @DisplayName("returns null for missing or blank path")
        void blankPath() {
            assertNull(InputFilterEvaluator.resolvePath(Map.of("a", 1), ""));
            assertNull(InputFilterEvaluator.resolvePath(null, "a.b"));
        }

        @Test
        @DisplayName("returns nested scalar")
        void nestedScalar() {
            Object value = InputFilterEvaluator.resolvePath(
                    Map.of("a", Map.of("b", Map.of("c", "v"))), "a.b.c");
            assertEquals("v", value);
        }
    }
}
