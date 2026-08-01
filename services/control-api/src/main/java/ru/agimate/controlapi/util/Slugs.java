package ru.agimate.controlapi.util;

import lombok.experimental.UtilityClass;

import java.util.Locale;
import java.util.Map;

/**
 * Human text → an ASCII slug. Two consumers with different rules but one transliteration table:
 * sheet column codes (substituted into SQL as JSONB keys) and connection handles (the prefix of the
 * tool namespace an agent sees). Cyrillic is data here, not prose — a name typed in Russian must
 * survive as something, and {@code replaceAll("[^a-z0-9]+", "_")} would erase it entirely.
 */
@UtilityClass
public class Slugs {

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

    /** Lowercase ASCII, everything else collapsed into single underscores, trimmed and truncated. */
    public static String slug(String source, int maxLength) {
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
        if (slug.length() > maxLength) {
            slug = slug.substring(0, maxLength).replaceAll("_+$", "");
        }
        return slug;
    }

    /** A slug usable where a leading digit is invalid: «2026 год» would otherwise start with one. */
    public static String identifier(String source, int maxLength) {
        String slug = slug(source, maxLength);
        if (!slug.isEmpty() && !Character.isLetter(slug.charAt(0))) {
            slug = "c_" + slug;
            if (slug.length() > maxLength) {
                slug = slug.substring(0, maxLength);
            }
        }
        return slug;
    }
}
