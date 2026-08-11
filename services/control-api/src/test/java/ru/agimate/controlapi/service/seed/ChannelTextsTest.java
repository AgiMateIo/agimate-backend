package ru.agimate.controlapi.service.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Полнота служебных реплик платформы в канале. Пропущенный ключ ничего не ломает — вернётся
 * английский фолбэк из кода, — поэтому на RU-инсталляции пользователь просто получит в чате
 * английскую фразу посреди русского разговора, и заметит это он, а не мы.
 *
 * <p>Ключи перечислены явно, как в {@link PromptTextsTest}: список и есть контракт «что обязано быть
 * переведено».
 */
@DisplayName("ChannelTexts — переводы служебных реплик в канале")
class ChannelTextsTest {

    private static final List<String> KEYS = List.of(ChannelTexts.NOTHING_TO_STOP);

    static Stream<Arguments> languageAndKey() {
        return Stream.of(ContentLanguage.values())
                .filter(language -> language != ContentLanguage.DEFAULT)
                .flatMap(language -> KEYS.stream().map(key -> Arguments.of(language, key)));
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("languageAndKey")
    @DisplayName("ключ переведён и не пуст")
    void everyKeyTranslated(ContentLanguage language, String key) {
        Properties texts = load(language);

        assertTrue(texts.containsKey(key), key + " отсутствует в " + language);
        assertFalse(texts.getProperty(key).isBlank(), key + " пустой в " + language);
    }

    private static Properties load(ContentLanguage language) {
        String path = "seed/texts/%s/channel.properties".formatted(language.dir());
        ClassPathResource resource = new ClassPathResource(path);
        assertTrue(resource.exists(), path + " отсутствует");
        Properties properties = new Properties();
        try (InputStream in = resource.getInputStream()) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }
}
