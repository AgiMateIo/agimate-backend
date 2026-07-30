package ru.agimate.controlapi.service.seed;

import lombok.experimental.UtilityClass;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reading of the seed files that carry data rather than text: a classpath YAML with a list of
 * mappings under a root key, plus the typed accessors its readers need.
 *
 * <p>YAML rather than CSV because the entries have list-valued fields; in a CSV cell they would
 * become JSON strings inside a string. Parsed through snakeyaml by hand rather than through Jackson —
 * the YAML dataformat module is only on the classpath transitively (springdoc), and a seed file is
 * not worth a dependency that can disappear under us.
 *
 * <p>Malformed input throws and takes the startup with it. That is the point: these files ship inside
 * the jar, so the only way to break one is a bad edit in our own tree, and the build checks them.
 * Semantic rules (unique keys, required combinations) belong to the tests of each seed — failing the
 * build is louder than failing a deploy, and this class stays about parsing.
 */
@UtilityClass
public class SeedYaml {

    /** The mappings under {@code rootKey}; {@code where} in messages is the path plus the index. */
    public static List<Map<?, ?>> entries(String path, String rootKey) {
        Object root = read(path).get(rootKey);
        if (!(root instanceof List<?> list)) {
            throw new IllegalStateException(path + ": expected a '" + rootKey + "' list at the root");
        }
        List<Map<?, ?>> entries = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> map)) {
                throw new IllegalStateException(path + ": " + rootKey + "[" + i + "] is not a mapping");
            }
            entries.add(map);
        }
        return entries;
    }

    private static Map<?, ?> read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map<?, ?> map)) {
                throw new IllegalStateException(path + ": expected a mapping at the root");
            }
            return map;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read the seed file: " + path, e);
        }
    }

    /** {@code null} for an absent or blank value — the seed says «not set», not «empty string». */
    public static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }

    public static String requireText(Map<?, ?> map, String key, String where) {
        String value = text(map, key);
        if (value == null) {
            throw new IllegalStateException(where + ": " + key + " is required");
        }
        return value;
    }

    /** {@code null} when the key is absent — the field is nullable in every current reader. */
    public static Integer integer(Map<?, ?> map, String key, String where) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalStateException(where + ": " + key + " must be a number");
        }
        return number.intValue();
    }

    public static List<String> strings(Map<?, ?> map, String key, String where) {
        return stringList(map.get(key), where, key);
    }

    /** Takes the raw object so a caller can pass a nested value (a map's entry) as well as a field. */
    public static List<String> stringList(Object raw, String where, String field) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException(where + ": " + field + " must be a list");
        }
        List<String> values = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String value) || value.isBlank()) {
                // A bare `~` is YAML's null, and some model ids start with a tilde — an unquoted
                // alias arrives here as null rather than as text.
                throw new IllegalStateException(where + ": " + field
                        + " contains a non-string value (an unquoted '~alias'?)");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    public static <E extends Enum<E>> E enumValue(Class<E> type, String value, String where, String field) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(where + ": unknown " + field + " '" + value + "'", e);
        }
    }
}
