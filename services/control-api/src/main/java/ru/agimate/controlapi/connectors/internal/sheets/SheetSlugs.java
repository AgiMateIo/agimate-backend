package ru.agimate.controlapi.connectors.internal.sheets;

import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.util.Slugs;

import java.util.Collection;

/**
 * An imported table's header → a column's machine code. Headers arrive human («Категория расхода»),
 * while a column name is substituted into SQL as a JSONB key and must be an ASCII slug — so we
 * transliterate and keep the original text in {@code title} (the same name-as-code / title-as-display
 * pair as skills and presets use).
 */
@UtilityClass
public class SheetSlugs {

    private static final int MAX_LENGTH = 48;

    /** A slug unique within {@code taken}; on a collision it appends a suffix {@code _2}, {@code _3}… */
    public static String unique(String source, Collection<String> taken, String fallback) {
        String base = slug(source);
        if (base.isEmpty()) {
            base = fallback;
        }
        String candidate = base;
        int suffix = 2;
        while (taken.contains(candidate)) {
            String tail = "_" + suffix++;
            candidate = base.length() + tail.length() > MAX_LENGTH
                    ? base.substring(0, MAX_LENGTH - tail.length()) + tail
                    : base + tail;
        }
        return candidate;
    }

    /** A column name must begin with a letter — hence {@code identifier}, not the bare slug. */
    public static String slug(String source) {
        return Slugs.identifier(source, MAX_LENGTH);
    }
}
