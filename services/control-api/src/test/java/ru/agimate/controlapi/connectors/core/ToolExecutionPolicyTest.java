package ru.agimate.controlapi.connectors.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ToolExecutionPolicy")
class ToolExecutionPolicyTest {

    @Nested
    @DisplayName("replayable()")
    class Replayable {

        @Test
        @DisplayName("вид REPLAYABLE и отсутствие таймаута")
        void hasReplayableKindAndNoTimeout() {
            var policy = ToolExecutionPolicy.replayable();

            assertEquals(ToolExecutionPolicy.Kind.REPLAYABLE, policy.kind());
            assertNull(policy.executionTimeout());
        }
    }

    @Nested
    @DisplayName("nonReplayable(Duration)")
    class NonReplayable {

        @Test
        @DisplayName("вид NON_REPLAYABLE и переданный таймаут")
        void keepsKindAndTimeout() {
            var policy = ToolExecutionPolicy.nonReplayable(Duration.ofSeconds(120));

            assertEquals(ToolExecutionPolicy.Kind.NON_REPLAYABLE, policy.kind());
            assertEquals(Duration.ofSeconds(120), policy.executionTimeout());
        }

        @Test
        @DisplayName("отсутствие таймаута отвергается")
        void rejectsNullTimeout() {
            assertThrows(IllegalArgumentException.class, () -> ToolExecutionPolicy.nonReplayable(null));
        }

        @Test
        @DisplayName("нулевой таймаут отвергается")
        void rejectsZeroTimeout() {
            assertThrows(IllegalArgumentException.class,
                    () -> ToolExecutionPolicy.nonReplayable(Duration.ZERO));
        }

        @Test
        @DisplayName("отрицательный таймаут отвергается")
        void rejectsNegativeTimeout() {
            assertThrows(IllegalArgumentException.class,
                    () -> ToolExecutionPolicy.nonReplayable(Duration.ofMillis(-1)));
        }
    }

    @Nested
    @DisplayName("канонический конструктор")
    class CanonicalConstructor {

        @Test
        @DisplayName("null kind отвергается")
        void rejectsNullKind() {
            assertThrows(IllegalArgumentException.class, () -> new ToolExecutionPolicy(null, null));
        }

        @Test
        @DisplayName("NON_REPLAYABLE без таймаута отвергается")
        void rejectsNonReplayableWithoutTimeout() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ToolExecutionPolicy(ToolExecutionPolicy.Kind.NON_REPLAYABLE, null));
        }

        @Test
        @DisplayName("NON_REPLAYABLE с нулевым таймаутом отвергается")
        void rejectsNonReplayableWithZeroTimeout() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ToolExecutionPolicy(ToolExecutionPolicy.Kind.NON_REPLAYABLE, Duration.ZERO));
        }

        @Test
        @DisplayName("REPLAYABLE с таймаутом отвергается")
        void rejectsReplayableWithTimeout() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ToolExecutionPolicy(ToolExecutionPolicy.Kind.REPLAYABLE, Duration.ofSeconds(1)));
        }

        @Test
        @DisplayName("корректное прямое создание сохраняет значения")
        void acceptsValidDirectConstruction() {
            var policy = new ToolExecutionPolicy(
                    ToolExecutionPolicy.Kind.NON_REPLAYABLE, Duration.ofSeconds(30));

            assertEquals(ToolExecutionPolicy.Kind.NON_REPLAYABLE, policy.kind());
            assertEquals(Duration.ofSeconds(30), policy.executionTimeout());
        }
    }
}
