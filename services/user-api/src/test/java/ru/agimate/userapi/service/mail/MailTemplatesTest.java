package ru.agimate.userapi.service.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MailTemplates — письма из ресурсов")
class MailTemplatesTest {

    @Nested
    @DisplayName("подстановка")
    class Rendering {

        @Test
        @DisplayName("значения попадают и в тему, и в тело")
        void substitutes() {
            MailTemplates templates = new MailTemplates("ru");

            MailTemplates.Letter letter = templates.render("test-message", Map.of("name", "Евгений"));

            assertEquals("Проверка отправки, Евгений", letter.subject());
            assertTrue(letter.html().contains("Здравствуйте, Евгений."), letter.html());
        }

        /**
         * A display name is written by the person it belongs to. It reaches the letter as text, and
         * markup inside it must show up as markup, not act as it.
         */
        @Test
        @DisplayName("подставленное экранируется — имя пишет сам человек")
        void escapes() {
            MailTemplates templates = new MailTemplates("ru");

            MailTemplates.Letter letter = templates.render("test-message",
                    Map.of("name", "<script>alert(1)</script>"));

            assertTrue(letter.html().contains("&lt;script&gt;"), letter.html());
        }

        @Test
        @DisplayName("незаполненный плейсхолдер — отказ, а не письмо с маркером на виду")
        void refusesUnfilled() {
            MailTemplates templates = new MailTemplates("ru");

            assertThrows(IllegalStateException.class, () -> templates.render("test-message", Map.of()));
        }
    }

    @Nested
    @DisplayName("язык")
    class Language {

        @Test
        @DisplayName("нет перевода — письмо уходит на английском")
        void fallsBack() {
            MailTemplates templates = new MailTemplates("ru");

            MailTemplates.Letter letter = templates.render("fallback-only", Map.of("name", "Eugene"));

            assertEquals("A letter with no translation", letter.subject());
            assertTrue(letter.html().contains("Hello, Eugene."), letter.html());
        }

        @Test
        @DisplayName("неизвестное письмо — отказ, а не пустое сообщение")
        void unknownLetter() {
            MailTemplates templates = new MailTemplates("ru");

            assertThrows(IllegalStateException.class, () -> templates.render("no-such-letter", Map.of()));
        }
    }
}
