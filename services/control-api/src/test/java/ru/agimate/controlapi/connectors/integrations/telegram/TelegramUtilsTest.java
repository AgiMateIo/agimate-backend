package ru.agimate.controlapi.connectors.integrations.telegram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.agimate.controlapi.connectors.integrations.telegram.TelegramUtils.normalizeUpdate;
import static ru.agimate.controlapi.connectors.integrations.telegram.TelegramUtils.splitMessage;
import static ru.agimate.controlapi.connectors.integrations.telegram.TelegramUtils.truncate;

@DisplayName("TelegramUtils")
class TelegramUtilsTest {

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
            List<String> chunks = splitMessage(text, TelegramUtils.MAX_MESSAGE_LENGTH);

            assertTrue(chunks.size() > 1, "should split into multiple chunks");
            for (String chunk : chunks) {
                assertTrue(chunk.length() <= TelegramUtils.MAX_MESSAGE_LENGTH,
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

    @Nested
    @DisplayName("normalizeUpdate")
    class NormalizeUpdate {

        private static final String IDENTITY = "integration-1";

        private Map<String, Object> message(Map<String, Object> extra) {
            Map<String, Object> message = new java.util.LinkedHashMap<>();
            message.put("message_id", 456);
            message.put("from", Map.of("id", 789, "first_name", "Test"));
            message.put("chat", Map.of("id", 100, "type", "private"));
            message.putAll(extra);
            return Map.of("update_id", 123, "message", message);
        }

        @Test
        @DisplayName("текстовое сообщение")
        void textMessage() {
            Trigger result = normalizeUpdate(message(Map.of("text", "Hello world")), IDENTITY);

            assertEquals("message_received", result.name());
            assertEquals("telegram", result.connectorCode());
            assertEquals(IDENTITY, result.identity());
            assertEquals("Hello world", result.data().get("text"));
            assertEquals(100, result.data().get("chatId"));
            assertEquals(456, result.data().get("messageId"));
        }

        @Test
        @DisplayName("команда с аргументами")
        void commandMessage() {
            Trigger result = normalizeUpdate(message(Map.of("text", "/start hello world")), IDENTITY);

            assertEquals("command_received", result.name());
            assertEquals("/start", result.data().get("command"));
            assertEquals("hello world", result.data().get("args"));
        }

        @Test
        @DisplayName("фото с подписью")
        void photoMessage() {
            Trigger result = normalizeUpdate(message(Map.of(
                    "photo", List.of(Map.of("file_id", "abc")),
                    "caption", "My photo")), IDENTITY);

            assertEquals("photo_received", result.name());
            assertEquals("My photo", result.data().get("caption"));
        }

        @Test
        @DisplayName("документ")
        void documentMessage() {
            Trigger result = normalizeUpdate(message(Map.of(
                    "document", Map.of("file_id", "doc123", "file_name", "test.pdf"))), IDENTITY);

            assertEquals("document_received", result.name());
        }

        @Test
        @DisplayName("callback query")
        void callbackQuery() {
            Map<String, Object> update = Map.of(
                    "update_id", 123,
                    "callback_query", Map.of(
                            "id", "cb123",
                            "from", Map.of("id", 789),
                            "data", "button_clicked",
                            "message", Map.of(
                                    "message_id", 456,
                                    "chat", Map.of("id", 100))));

            Trigger result = normalizeUpdate(update, IDENTITY);

            assertEquals("callback_query", result.name());
            assertEquals("cb123", result.data().get("callbackQueryId"));
            assertEquals("button_clicked", result.data().get("data"));
            assertEquals(100, result.data().get("chatId"));
        }

        @Test
        @DisplayName("неизвестный тип update")
        void unknownUpdate() {
            Map<String, Object> update = Map.of("update_id", 123, "poll", Map.of("id", "p1"));

            Trigger result = normalizeUpdate(update, IDENTITY);

            assertEquals("unknown", result.name());
            assertEquals(update, result.data().get("raw"));
        }
    }
}
