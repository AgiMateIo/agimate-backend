package ru.agimate.userapi.service.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MailTemplates")
class MailTemplatesTest {

    @Nested
    @DisplayName("rendering")
    class Rendering {

        @Test
        @DisplayName("substitutes into the subject and the body alike")
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
        @DisplayName("escapes what it substitutes")
        void escapes() {
            MailTemplates templates = new MailTemplates("ru");

            MailTemplates.Letter letter = templates.render("test-message",
                    Map.of("name", "<script>alert(1)</script>"));

            assertTrue(letter.html().contains("&lt;script&gt;"), letter.html());
        }

        @Test
        @DisplayName("refuses to send a letter with a placeholder left in it")
        void refusesUnfilled() {
            MailTemplates templates = new MailTemplates("ru");

            assertThrows(IllegalStateException.class, () -> templates.render("test-message", Map.of()));
        }
    }

    @Nested
    @DisplayName("language")
    class Language {

        @Test
        @DisplayName("falls back to English when the letter has no translation")
        void fallsBack() {
            MailTemplates templates = new MailTemplates("ru");

            MailTemplates.Letter letter = templates.render("fallback-only", Map.of("name", "Eugene"));

            assertEquals("A letter with no translation", letter.subject());
            assertTrue(letter.html().contains("Hello, Eugene."), letter.html());
        }

        @Test
        @DisplayName("an unknown letter is a failure, not an empty message")
        void unknownLetter() {
            MailTemplates templates = new MailTemplates("ru");

            assertThrows(IllegalStateException.class, () -> templates.render("no-such-letter", Map.of()));
        }
    }
}
