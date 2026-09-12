package ru.agimate.controlapi.service.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import ru.agimate.controlapi.config.ContentProperties;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.database.enums.ContentTag;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Полнота перевода словаря каталога. Пропущенный ключ ничего не роняет — {@link TaxonomyTexts} молча
 * отдаёт английскую подпись из enum'а, — и на RU-инсталляции пользователь увидел бы навигацию, где
 * половина разделов по-английски. Новое значение словаря без перевода валит именно этот тест.
 */
@DisplayName("TaxonomyTexts — переводы словаря каталога")
class TaxonomyTextsTest {

    private static Stream<ContentLanguage> translations() {
        return Stream.of(ContentLanguage.values()).filter(language -> language != ContentLanguage.DEFAULT);
    }

    /** Все ключи словаря: категории, группы тегов и сами теги. */
    private static List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (ContentCategory category : ContentCategory.values()) {
            keys.add("category." + TaxonomyTexts.code(category.name()));
        }
        for (ContentTag.Group group : ContentTag.Group.values()) {
            keys.add("tag-group." + TaxonomyTexts.code(group.name()));
        }
        for (ContentTag tag : ContentTag.values()) {
            keys.add("tag." + TaxonomyTexts.code(tag.name()));
        }
        return keys;
    }

    static Stream<Arguments> languageAndKey() {
        return translations().flatMap(language -> keys().stream().map(key -> Arguments.of(language, key)));
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("languageAndKey")
    @DisplayName("у каждого значения словаря есть непустая подпись")
    void everyValueTranslated(ContentLanguage language, String key) {
        Properties texts = load(language);

        assertTrue(texts.containsKey(key), key + " отсутствует в " + language);
        assertTrue(!texts.getProperty(key).isBlank(), key + " пустой");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("translations")
    @DisplayName("нет ключей для значений, которых нет в словаре")
    void noOrphanKeys(ContentLanguage language) {
        List<String> orphans = load(language).stringPropertyNames().stream()
                .filter(key -> !keys().contains(key))
                .sorted()
                .toList();

        assertEquals(List.of(), orphans, "перевод для несуществующего значения — опечатка в ключе");
    }

    @Nested
    @DisplayName("Резолв")
    class Resolution {

        private TaxonomyTexts texts(ContentLanguage language) {
            ContentProperties properties = new ContentProperties();
            properties.setLanguage(language);
            return new TaxonomyTexts(properties);
        }

        @Test
        @DisplayName("язык-первоисточник отдаёт подпись из enum'а")
        void defaultLanguageUsesCode() {
            assertEquals(ContentCategory.FINANCE.getLabel(), texts(ContentLanguage.DEFAULT).label(ContentCategory.FINANCE));
        }

        @Test
        @DisplayName("перевод перекрывает подпись из enum'а")
        void translationOverridesCode() {
            TaxonomyTexts texts = texts(ContentLanguage.RU);

            assertNotEquals(ContentTag.OWN_TOKEN.getLabel(), texts.label(ContentTag.OWN_TOKEN));
            assertNotEquals(ContentTag.Group.FEATURE.getLabel(), texts.label(ContentTag.Group.FEATURE));
        }
    }

    private static Properties load(ContentLanguage language) {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource("seed/texts/%s/taxonomy.properties".formatted(language.dir()))
                .getInputStream()) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Нет seed/texts/" + language.dir() + "/taxonomy.properties", e);
        }
        return properties;
    }
}
