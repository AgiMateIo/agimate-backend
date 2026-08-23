package ru.agimate.userapi.service.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Against the real letters in {@code src/main/resources/mail}, and deliberately so: fixtures of its
 * own used to live at the same classpath path and hid the production files from every test here,
 * which left the letters people actually receive as the one thing this could not check.
 */
@DisplayName("MailTemplates — письма из ресурсов")
class MailTemplatesTest {

    private static Map<String, String> variables() {
        Map<String, String> variables = new HashMap<>();
        variables.put("name", "Евгений");
        variables.put("link", "https://www.agimate.ru/password/reset?token=abc");
        variables.put("hours", "1");
        variables.put("provider", "GitHub");
        return variables;
    }

    @Nested
    @DisplayName("боевые письма")
    class Production {

        @ParameterizedTest(name = "{0} на языке {1}")
        @CsvSource({
                "password-reset, ru", "password-reset, en",
                "registration-confirm, ru", "registration-confirm, en",
                "account-exists, ru", "account-exists, en",
                "provider-linked, ru", "provider-linked, en",
                "provider-unlinked, ru", "provider-unlinked, en",
                "password-changed, ru", "password-changed, en",
                "password-removed, ru", "password-removed, en",
        })
        @DisplayName("рендерится целиком: и тема, и тело с обращением")
        void renders(String letter, String language) {
            MailTemplates.Letter rendered = new MailTemplates(language).render(letter, variables());

            assertFalse(rendered.subject().isBlank(), "тема пуста");
            assertTrue(rendered.html().contains("Евгений"), rendered.html());
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"password-reset", "registration-confirm", "account-exists"})
        @DisplayName("письма со ссылкой её и несут")
        void carriesTheLink(String letter) {
            MailTemplates.Letter rendered = new MailTemplates("ru").render(letter, variables());

            assertTrue(rendered.html().contains("https://www.agimate.ru/password/reset?token=abc"),
                    rendered.html());
        }
    }

    @Nested
    @DisplayName("подстановка")
    class Substitution {

        @Test
        @DisplayName("значения попадают и в тему, и в тело")
        void substitutes() {
            MailTemplates.Letter letter = new MailTemplates("ru").render("password-reset", variables());

            assertTrue(letter.subject().contains("AgiMate"), letter.subject());
            assertTrue(letter.html().contains("Здравствуйте, Евгений."), letter.html());
        }

        @Test
        @DisplayName("подставленное экранируется — имя пишет сам человек")
        void escapes() {
            Map<String, String> variables = variables();
            variables.put("name", "<script>alert(1)</script>");

            MailTemplates.Letter letter = new MailTemplates("ru").render("password-reset", variables);

            assertTrue(letter.html().contains("&lt;script&gt;"), letter.html());
        }

        /**
         * Подстановка идёт одной проходкой по маркерам, а не проходкой на переменную: иначе значение,
         * само содержащее маркер, было бы прочитано как маркер — и письмо не ушло бы вовсе.
         */
        @Test
        @DisplayName("значение с маркером внутри не ломает письмо")
        void valueCarryingAMarker() {
            Map<String, String> variables = variables();
            variables.put("name", "{{link}}");

            MailTemplates.Letter letter = new MailTemplates("ru").render("password-reset", variables);

            assertTrue(letter.html().contains("{{link}}"), letter.html());
            assertTrue(letter.html().contains("token=abc"), "ссылка должна была подставиться отдельно");
        }

        @Test
        @DisplayName("незаполненный плейсхолдер — отказ, а не письмо с маркером на виду")
        void refusesUnfilled() {
            assertThrows(IllegalStateException.class,
                    () -> new MailTemplates("ru").render("password-reset", Map.of()));
        }
    }

    @Nested
    @DisplayName("язык")
    class Language {

        @Test
        @DisplayName("нет перевода — письмо уходит на английском")
        void fallsBack() {
            MailTemplates.Letter letter = new MailTemplates("de").render("password-reset", variables());

            assertEquals(new MailTemplates("en").render("password-reset", variables()).subject(),
                    letter.subject());
        }

        @Test
        @DisplayName("неизвестное письмо — отказ, а не пустое сообщение")
        void unknownLetter() {
            assertThrows(IllegalStateException.class,
                    () -> new MailTemplates("ru").render("no-such-letter", Map.of()));
        }
    }
}
