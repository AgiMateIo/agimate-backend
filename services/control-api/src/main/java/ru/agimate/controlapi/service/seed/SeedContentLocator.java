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
 * The only place that knows the layout of seed content:
 * {@code resources/seed/<lang>/<presets|skills>/<code>/<FILE>.md}. The bootstraps address content by
 * code ({@code personal-assistant}, {@code time}) rather than by path — the locator substitutes the
 * language.
 *
 * <p>A missing file for the chosen language is not a refusal: we read
 * {@link ContentLanguage#DEFAULT} and log a warning. Otherwise an untranslated skill would throw
 * everything referencing it out of the catalogue; language parity is guaranteed by a test, so in a
 * built release the fallback never fires.
 */
@Slf4j
@Component
public class SeedContentLocator {

    /** Kind of seed content: the subfolder in {@code seed/<lang>/} and the file's name inside the code's folder. */
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

    /** The contents for the installation's language; a miss → {@link ContentLanguage#DEFAULT}. */
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
