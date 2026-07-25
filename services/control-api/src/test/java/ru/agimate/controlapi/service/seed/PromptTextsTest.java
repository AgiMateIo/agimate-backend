package ru.agimate.controlapi.service.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import ru.agimate.controlapi.config.ContentProperties;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Полнота переводов доверенных блоков промпта. Пропущенный ключ не роняет ничего — фолбэк отдаёт
 * русский, — поэтому на EN-инсталляции агент молча получал бы смешанный промпт: английские
 * инструкции и скилы плюс русские правила поведения.
 *
 * <p>Ключи перечислены здесь явно, а не собираются рефлексией: список — это и есть контракт
 * «что обязано быть переведено», и новый блок промпта должен валить сборку, пока перевода нет.
 */
@DisplayName("PromptTexts — переводы доверенных блоков промпта")
class PromptTextsTest {

    /** Платформенные блоки: применяются к каждому подходящему рану независимо от коннекторов. */
    private static final List<String> PLATFORM_KEYS = List.of(
            PromptTexts.RUN_TRIGGER_GUIDANCE,
            PromptTexts.RUN_TOOL_CALL_GUIDANCE,
            PromptTexts.RUN_ATTACHMENT_GUIDANCE);

    /** Инструкции реакции на события: только коннекторы, объявившие ContextDirectives.guidance. */
    private static final List<String> CONNECTOR_KEYS = List.of(
            "connector.board.guidance",
            "connector.time.due.guidance");

    private static Stream<ContentLanguage> translations() {
        return Stream.of(ContentLanguage.values()).filter(language -> language != ContentLanguage.DEFAULT);
    }

    static Stream<Arguments> languageAndKey() {
        return translations().flatMap(language ->
                Stream.concat(PLATFORM_KEYS.stream(), CONNECTOR_KEYS.stream())
                        .map(key -> Arguments.of(language, key)));
    }

    @ParameterizedTest(name = "{0} — {1}")
    @MethodSource("languageAndKey")
    @DisplayName("ключ есть и непустой")
    void everyKeyTranslated(ContentLanguage language, String key) {
        Properties texts = load(language);

        assertTrue(texts.containsKey(key), key + " отсутствует в " + language);
        assertFalse(texts.getProperty(key).isBlank(), key + " пустой в " + language);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("translations")
    @DisplayName("нет ключей, которых не знает код")
    void noOrphanKeys(ContentLanguage language) {
        List<String> known = Stream.concat(PLATFORM_KEYS.stream(), CONNECTOR_KEYS.stream()).toList();

        List<String> orphans = load(language).stringPropertyNames().stream()
                .filter(key -> !known.contains(key))
                .sorted()
                .toList();

        assertEquals(List.of(), orphans, "перевод под ключ, которого нет в коде — опечатка");
    }

    @Nested
    @DisplayName("Резолв")
    class Resolution {

        private PromptTexts texts(ContentLanguage language) {
            ContentProperties properties = new ContentProperties();
            properties.setLanguage(language);
            return new PromptTexts(properties);
        }

        @Test
        @DisplayName("язык-первоисточник отдаёт значение из кода")
        void defaultLanguageUsesCode() {
            PromptTexts texts = texts(ContentLanguage.DEFAULT);

            assertEquals("блок из кода", texts.get(PromptTexts.RUN_TRIGGER_GUIDANCE, "блок из кода"));
        }

        @Test
        @DisplayName("перевод перекрывает значение из кода")
        void translationOverridesCode() {
            PromptTexts texts = texts(ContentLanguage.EN);

            assertNotEquals("блок из кода", texts.get(PromptTexts.RUN_TRIGGER_GUIDANCE, "блок из кода"));
        }

        /** Board держит одну инструкцию на два события — она обязана находиться по обоим именам. */
        @Test
        @DisplayName("guidance коннектора находится по общему ключу для любого его триггера")
        void connectorGuidanceFallsBackToConnectorWideKey() {
            PromptTexts texts = texts(ContentLanguage.EN);

            String created = texts.triggerGuidance("board", "task_created", "код");
            String changed = texts.triggerGuidance("board", "task_changed", "код");

            assertEquals(created, changed, "оба события board обязаны получить одну инструкцию");
            assertNotEquals("код", created, "перевод не найден — фолбэк на connector.board.guidance не сработал");
        }

        @Test
        @DisplayName("коннектор без перевода — значение из кода")
        void unknownConnectorFallsBackToCode() {
            PromptTexts texts = texts(ContentLanguage.EN);

            assertEquals("из кода", texts.triggerGuidance("no-such-connector", "event", "из кода"));
        }
    }

    private static Properties load(ContentLanguage language) {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource("seed/%s/prompt.properties".formatted(language.dir()))
                .getInputStream()) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Нет seed/" + language.dir() + "/prompt.properties", e);
        }
        return properties;
    }
}
