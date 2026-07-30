package ru.agimate.controlapi.service.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import ru.agimate.controlapi.config.ContentProperties;
import ru.agimate.controlapi.service.llm.catalog.LlmCatalogSeed;
import ru.agimate.controlapi.service.llm.catalog.LlmCatalogSeedEntry;

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
 * Полнота перевода каталога провайдеров. Пропущенный ключ ничего не роняет — {@link LlmCatalogTexts}
 * молча отдаёт английский текст из сида, — поэтому на RU-инсталляции описание провайдера просто
 * осталось бы английским, и увидел бы это только пользователь.
 *
 * <p>Коды берутся из самого сида, а не дублируются списком: файл читается без Spring, так что
 * дублировать нечего — новый провайдер без перевода валит именно этот тест.
 */
@DisplayName("LlmCatalogTexts — переводы каталога LLM-провайдеров")
class LlmCatalogTextsTest {

    private static final List<String> CODES = LlmCatalogSeed.load().stream()
            .map(LlmCatalogSeedEntry::code)
            .toList();

    private static Stream<ContentLanguage> translations() {
        return Stream.of(ContentLanguage.values()).filter(language -> language != ContentLanguage.DEFAULT);
    }

    static Stream<Arguments> languageAndCode() {
        return translations().flatMap(language ->
                CODES.stream().map(code -> Arguments.of(language, code)));
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("languageAndCode")
    @DisplayName("у каждого провайдера есть description")
    void everyProviderTranslated(ContentLanguage language, String code) {
        Properties texts = load(language);

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
                .filter(code -> !CODES.contains(code))
                .toList();

        assertEquals(List.of(), orphans, "перевод для несуществующих провайдеров — опечатка в ключе");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("translations")
    @DisplayName("названия не переводятся — ключей .name быть не должно")
    void namesAreNotTranslated(ContentLanguage language) {
        List<String> nameKeys = load(language).stringPropertyNames().stream()
                .filter(key -> key.endsWith(".name"))
                .toList();

        // Бренд одинаков на любой инсталляции; ключ для него — приглашение транслитерировать.
        assertEquals(List.of(), nameKeys, "название провайдера — бренд, переводить его нечем");
    }

    @Nested
    @DisplayName("Резолв")
    class Resolution {

        private LlmCatalogTexts texts(ContentLanguage language) {
            ContentProperties properties = new ContentProperties();
            properties.setLanguage(language);
            return new LlmCatalogTexts(properties);
        }

        @Test
        @DisplayName("язык-первоисточник отдаёт значение из сида")
        void defaultLanguageUsesSeed() {
            LlmCatalogTexts texts = texts(ContentLanguage.DEFAULT);

            assertEquals("Gateway", texts.description("openrouter", "Gateway"));
        }

        @Test
        @DisplayName("перевод перекрывает значение из сида")
        void translationOverridesSeed() {
            LlmCatalogTexts texts = texts(ContentLanguage.RU);

            assertNotEquals("Gateway", texts.description("openrouter", "Gateway"));
        }

        @Test
        @DisplayName("нет ключа — значение из сида")
        void unknownCodeFallsBackToSeed() {
            LlmCatalogTexts texts = texts(ContentLanguage.RU);

            assertEquals("Заглушка", texts.description("no-such-provider", "Заглушка"));
        }
    }

    private static Properties load(ContentLanguage language) {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource("seed/texts/%s/llm-providers.properties".formatted(language.dir()))
                .getInputStream()) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Нет seed/texts/" + language.dir() + "/llm-providers.properties", e);
        }
        return properties;
    }
}
