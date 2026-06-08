package ru.agimate.controlapi.connectors.integrations.telegram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.agimate.controlapi.connectors.integrations.telegram.TelegramHandler.splitMessage;
import static ru.agimate.controlapi.connectors.integrations.telegram.TelegramHandler.truncate;

@DisplayName("TelegramHandler.splitMessage")
class TelegramMessageSplitTest {

    @Nested
    @DisplayName("when text fits the limit")
    class WithinLimit {

        @Test
        @DisplayName("returns the text as a single chunk")
        void singleChunk() {
            assertEquals(List.of("hello"), splitMessage("hello", 4096));
        }

        @Test
        @DisplayName("keeps an empty string as one chunk")
        void emptyStaysOne() {
            assertEquals(List.of(""), splitMessage("", 4096));
        }

        @Test
        @DisplayName("text exactly at the limit is not split")
        void exactlyAtLimit() {
            String text = "a".repeat(10);
            assertEquals(List.of(text), splitMessage(text, 10));
        }
    }

    @Nested
    @DisplayName("when text exceeds the limit")
    class OverLimit {

        @Test
        @DisplayName("breaks on the last newline within the window")
        void breaksOnNewline() {
            // limit 10; first line + newline at index 5, second run after it
            List<String> chunks = splitMessage("line1\nline2-tail", 10);
            assertEquals(List.of("line1", "line2-tail"), chunks);
        }

        @Test
        @DisplayName("breaks on the last whitespace when there is no newline")
        void breaksOnSpace() {
            List<String> chunks = splitMessage("aaaa bbbb cccc", 10);
            assertEquals(List.of("aaaa bbbb", "cccc"), chunks);
        }

        @Test
        @DisplayName("hard-cuts a run with no newline or space")
        void hardCut() {
            String text = "a".repeat(25);
            List<String> chunks = splitMessage(text, 10);
            assertEquals(List.of("a".repeat(10), "a".repeat(10), "a".repeat(5)), chunks);
        }

        @Test
        @DisplayName("every chunk respects the limit and content is preserved")
        void respectsLimitAndPreservesContent() {
            String text = ("word ".repeat(3000)).trim(); // ~15k chars, well over 4096
            List<String> chunks = splitMessage(text, TelegramHandler.MAX_MESSAGE_LENGTH);

            assertTrue(chunks.size() > 1, "should split into multiple chunks");
            for (String chunk : chunks) {
                assertTrue(chunk.length() <= TelegramHandler.MAX_MESSAGE_LENGTH,
                        "chunk exceeds limit: " + chunk.length());
            }
            // Boundaries dropped only single spaces between words → joining with a space restores the text.
            assertEquals(text, String.join(" ", chunks));
        }
    }

    @Nested
    @DisplayName("truncate")
    class Truncate {

        @Test
        @DisplayName("returns text unchanged when within the limit")
        void withinLimit() {
            assertEquals("hello", truncate("hello", 10));
        }

        @Test
        @DisplayName("returns text unchanged when exactly at the limit")
        void exactlyAtLimit() {
            String text = "a".repeat(10);
            assertEquals(text, truncate(text, 10));
        }

        @Test
        @DisplayName("passes null through")
        void nullPassesThrough() {
            assertNull(truncate(null, 10));
        }

        @Test
        @DisplayName("cuts to the limit with an ellipsis, never exceeding it")
        void cutsWithEllipsis() {
            String result = truncate("a".repeat(25), 10);
            assertEquals("a".repeat(9) + "…", result);
            assertEquals(10, result.length());
        }
    }
}
