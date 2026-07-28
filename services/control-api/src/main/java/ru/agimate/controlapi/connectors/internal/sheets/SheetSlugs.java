package ru.agimate.controlapi.connectors.internal.sheets;

import lombok.experimental.UtilityClass;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * An imported table's header → a column's machine code. Headers arrive human («Категория расхода»),
 * while a column name is substituted into SQL as a JSONB key and must be an ASCII slug — so we
 * transliterate and keep the original text in {@code title} (the same name-as-code / title-as-display
 * pair as skills and presets use).
 */
@UtilityClass
public class SheetSlugs {

    private static final Map<Character, String> CYRILLIC = Map.ofEntries(
            Map.entry('а', "a"), Map.entry('б', "b"), Map.entry('в', "v"), Map.entry('г', "g"),
            Map.entry('д', "d"), Map.entry('е', "e"), Map.entry('ё', "e"), Map.entry('ж', "zh"),
            Map.entry('з', "z"), Map.entry('и', "i"), Map.entry('й', "i"), Map.entry('к', "k"),
            Map.entry('л', "l"), Map.entry('м', "m"), Map.entry('н', "n"), Map.entry('о', "o"),
            Map.entry('п', "p"), Map.entry('р', "r"), Map.entry('с', "s"), Map.entry('т', "t"),
            Map.entry('у', "u"), Map.entry('ф', "f"), Map.entry('х', "h"), Map.entry('ц', "c"),
            Map.entry('ч', "ch"), Map.entry('ш', "sh"), Map.entry('щ', "sch"), Map.entry('ъ', ""),
            Map.entry('ы', "y"), Map.entry('ь', ""), Map.entry('э', "e"), Map.entry('ю', "yu"),
            Map.entry('я', "ya"));

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

    public static String slug(String source) {
        if (source == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(source.length());
        for (char symbol : source.toLowerCase(Locale.ROOT).toCharArray()) {
            String mapped = CYRILLIC.get(symbol);
            if (mapped != null) {
                out.append(mapped);
            } else if (symbol >= 'a' && symbol <= 'z' || symbol >= '0' && symbol <= '9') {
                out.append(symbol);
            } else {
                out.append('_');
            }
        }
        String slug = out.toString().replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        if (slug.length() > MAX_LENGTH) {
            slug = slug.substring(0, MAX_LENGTH).replaceAll("_+$", "");
        }
        // The name must begin with a letter: «2026 год» would produce an invalid one.
        if (!slug.isEmpty() && !Character.isLetter(slug.charAt(0))) {
            slug = ("c_" + slug);
            if (slug.length() > MAX_LENGTH) {
                slug = slug.substring(0, MAX_LENGTH);
            }
        }
        return slug;
    }
}
