package ru.agimate.controlapi.service.seed;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import ru.agimate.controlapi.config.ContentProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Единственное место, знающее раскладку сид-контента: {@code resources/seed/<lang>/<presets|skills>/<code>/<FILE>.md}.
 * Бутстрапы адресуют контент кодом ({@code personal-assistant}, {@code time}), а не путём — язык
 * подставляет локатор.
 *
 * <p>Нет файла для выбранного языка — не отказ: читаем {@link ContentLanguage#DEFAULT} и пишем
 * warning. Иначе непереведённый скилл выкинул бы из каталога всё, что на него ссылается; паритет
 * языков обеспечивается тестом, так что в собранном релизе фолбэк не срабатывает.
 */
@Slf4j
@Component
public class SeedContentLocator {

    /** Вид сид-контента: подпапка в {@code seed/<lang>/} и имя файла внутри папки кода. */
    @Getter
    public enum Kind {

        PRESET("presets", "PRESET.md"),
        SKILL("skills", "SKILL.md");

        private final String dir;
        private final String fileName;

        Kind(String dir, String fileName) {
            this.dir = dir;
            this.fileName = fileName;
        }
    }

    @Getter
    private final ContentLanguage language;

    public SeedContentLocator(ContentProperties contentProperties) {
        this.language = contentProperties.getLanguage();
    }

    /** Содержимое для языка инсталляции; промах → {@link ContentLanguage#DEFAULT}. */
    public String read(Kind kind, String code) {
        if (language != ContentLanguage.DEFAULT && !exists(kind, code, language)) {
            log.warn("No {} '{}' for language {} — falling back to {}", kind, code, language, ContentLanguage.DEFAULT);
            return read(kind, code, ContentLanguage.DEFAULT);
        }
        return read(kind, code, language);
    }

    public static String path(Kind kind, String code, ContentLanguage language) {
        return "seed/%s/%s/%s/%s".formatted(language.dir(), kind.getDir(), code, kind.getFileName());
    }

    public static boolean exists(Kind kind, String code, ContentLanguage language) {
        return new ClassPathResource(path(kind, code, language)).exists();
    }

    public static String read(Kind kind, String code, ContentLanguage language) {
        String path = path(kind, code, language);
        try {
            return StreamUtils.copyToString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read seed content: " + path, e);
        }
    }
}
