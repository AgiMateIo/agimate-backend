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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Полнота перевода каталога коннекторов. Пропущенный ключ не роняет ничего — {@link ConnectorTexts}
 * молча отдаёт значение из кода, — поэтому на RU-инсталляции половина каталога подключений просто
 * осталась бы английской, и заметил бы это только пользователь.
 *
 * <p>Список кодов дублируется здесь намеренно: реестр коннекторов собирается Spring'ом, а тест
 * держится на статике, чтобы падать на сборке, а не на старте контекста. Новый коннектор без
 * перевода валит именно этот тест.
 */
@DisplayName("ConnectorTexts — переводы каталога коннекторов")
class ConnectorTextsTest {

    /** Все коды каталога: хендлеры из SPI плюс статические строки ConnectorBootstrap. */
    private static final List<String> CONNECTOR_CODES = List.of(
            "app", "claude-code",
            "acp", "astro", "board", "divination", "mcp", "media", "persist-memory",
            "platform", "sheets", "telegram", "time", "webchat");

    private static Stream<ContentLanguage> translations() {
        return Stream.of(ContentLanguage.values()).filter(language -> language != ContentLanguage.DEFAULT);
    }

    static Stream<Arguments> languageAndCode() {
        return translations().flatMap(language ->
                CONNECTOR_CODES.stream().map(code -> Arguments.of(language, code)));
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("languageAndCode")
    @DisplayName("у каждого коннектора есть name и description")
    void everyConnectorTranslated(ContentLanguage language, String code) {
        Properties texts = load(language);

        assertTrue(texts.containsKey(code + ".name"), code + ".name отсутствует в " + language);
        assertTrue(texts.containsKey(code + ".description"), code + ".description отсутствует в " + language);
        assertTrue(!texts.getProperty(code + ".description").isBlank(), code + ".description пустой");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("translations")
    @DisplayName("нет ключей для кодов, которых нет в каталоге")
    void noOrphanKeys(ContentLanguage language) {
        List<String> orphans = load(language).stringPropertyNames().stream()
                .map(key -> key.substring(0, key.lastIndexOf('.')))
                .distinct()
                .filter(code -> !CONNECTOR_CODES.contains(code))
                .toList();

        assertEquals(List.of(), orphans, "перевод для несуществующих коннекторов — опечатка в ключе");
    }

    @Nested
    @DisplayName("Резолв")
    class Resolution {

        private ConnectorTexts texts(ContentLanguage language) {
            ContentProperties properties = new ContentProperties();
            properties.setLanguage(language);
            return new ConnectorTexts(properties);
        }

        @Test
        @DisplayName("язык-первоисточник отдаёт значение из кода")
        void defaultLanguageUsesCode() {
            ConnectorTexts texts = texts(ContentLanguage.DEFAULT);

            assertEquals("Board", texts.name("board", "Board"));
        }

        @Test
        @DisplayName("перевод перекрывает значение из кода")
        void translationOverridesCode() {
            ConnectorTexts texts = texts(ContentLanguage.RU);

            assertNotEquals("The team's Kanban board", texts.description("board", "The team's Kanban board"));
        }

        @Test
        @DisplayName("нет ключа — значение из кода")
        void unknownCodeFallsBackToCode() {
            ConnectorTexts texts = texts(ContentLanguage.RU);

            assertEquals("Заглушка", texts.name("no-such-connector", "Заглушка"));
        }
    }

    private static Properties load(ContentLanguage language) {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource("seed/texts/%s/connectors.properties".formatted(language.dir()))
                .getInputStream()) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Нет seed/texts/" + language.dir() + "/connectors.properties", e);
        }
        return properties;
    }
}
