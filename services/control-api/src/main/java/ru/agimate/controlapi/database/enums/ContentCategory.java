package ru.agimate.controlapi.database.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What a skill or a preset is about — the one axis the catalogue is cut along, shared by both so the
 * user meets a single set of sections. Everything that is not the subject (who it is for, what it
 * does, what it needs) lives in {@link ContentTag}. See {@code docs/decisions/content-taxonomy.md}.
 *
 * <p>{@link #PLATFORM} is what makes one vocabulary enough for both: long-term memory, tables and time
 * belong to every sphere of life at once and to none of them in particular.
 *
 * <p>The English label is the source and the last fallback; translations live in
 * {@code seed/texts/<lang>/taxonomy.properties} (see {@code TaxonomyTexts}).
 */
@Getter
public enum ContentCategory {

    PLATFORM("Platform"),
    WORK("Work and business"),
    FINANCE("Money and finance"),
    HOME("Home and everyday life"),
    HEALTH("Health"),
    LEARNING("Learning and development"),
    CONTENT("Creativity and content"),
    DEVELOPMENT("Development"),
    COMMUNICATION("Communication"),
    LEISURE("Leisure and hobbies"),
    /** The default: «not filed yet». No system skill or preset may stay here — a test guards it. */
    OTHER("Other");

    private final String label;

    ContentCategory(String label) {
        this.label = label;
    }

    public static String codes() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
