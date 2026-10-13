package ru.agimate.controlapi.service.seed;

import lombok.Getter;
import lombok.experimental.UtilityClass;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import ru.agimate.controlapi.database.enums.ContentLanguage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The only place that knows the layout of seed content:
 * {@code resources/seed/<presets|skills>/<lang>/<code>/<FILE>.md}. The bootstraps address content by
 * code ({@code personal-assistant}, {@code time}) and language.
 *
 * <p>Kind first, language second, so the root of {@code seed/} reads as the list of things the
 * platform seeds — and content with no language dimension at all ({@code llm-providers.yaml}) has an
 * obvious place in it. Translation bundles are their own kind, {@code seed/texts/<lang>/}
 * ({@link SeedTextBundle}).
 *
 * <p>There is no fallback to another language: each language is a catalogue of its own, and a code
 * missing from a language's folder is simply not in that catalogue.
 */
@UtilityClass
public class SeedContentLocator {

    /** Kind of seed content: the folder in {@code seed/} and the file's name inside the code's folder. */
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

    public static String path(Kind kind, String code, ContentLanguage language) {
        return "seed/%s/%s/%s/%s".formatted(kind.getDir(), language.dir(), code, kind.getFileName());
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
