package ru.agimate.controlapi.service.llm.catalog;

import lombok.experimental.UtilityClass;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.enums.MediaTransportType;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Reader of {@code seed/llm-providers.yaml}.
 *
 * <p>YAML rather than CSV because {@code purposePriority} is a map of lists: in a CSV cell it would
 * become a JSON string inside a string. Parsed by hand instead of through Jackson — the YAML
 * dataformat module is only on the classpath transitively (springdoc), and this file is not worth a
 * dependency that can disappear under us.
 *
 * <p>A malformed file throws and takes the startup with it. That is the point: the seed ships inside
 * the jar, so the only way to break it is a bad edit in our own tree, and it is checked by
 * {@code LlmCatalogSeedTest} in the build. Semantic rules (unique codes, base_url where the provider
 * type demands one) live in that test rather than here — failing the build is louder than failing a
 * deploy, and this class stays about parsing.
 */
@UtilityClass
public class LlmCatalogSeed {

    public static final String SEED_PATH = "seed/llm-providers.yaml";

    public static List<LlmCatalogSeedEntry> load() {
        return load(SEED_PATH);
    }

    public static List<LlmCatalogSeedEntry> load(String path) {
        Object providers = read(path).get("providers");
        if (!(providers instanceof List<?> list)) {
            throw new IllegalStateException(path + ": expected a 'providers' list at the root");
        }
        List<LlmCatalogSeedEntry> entries = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            entries.add(toEntry(list.get(i), "providers[" + i + "]"));
        }
        return List.copyOf(entries);
    }

    private static Map<?, ?> read(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map<?, ?> map)) {
                throw new IllegalStateException(path + ": expected a mapping at the root");
            }
            return map;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read the LLM provider catalogue seed: " + path, e);
        }
    }

    private static LlmCatalogSeedEntry toEntry(Object raw, String where) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalStateException(where + ": expected a mapping");
        }
        String code = requireText(map, "code", where);
        String at = "provider '" + code + "'";
        return new LlmCatalogSeedEntry(
                code,
                requireText(map, "name", at),
                requireText(map, "description", at),
                enumValue(LlmProviderType.class, requireText(map, "providerType", at), at, "providerType"),
                text(map, "baseUrl"),
                enumValue(MediaTransportType.class, text(map, "mediaTransport"), at, "mediaTransport"),
                text(map, "apiKeyUrl"),
                intValue(map, "sortOrder", at),
                purposePriority(map.get("purposePriority"), at));
    }

    private static Map<LlmPurpose, List<String>> purposePriority(Object raw, String where) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalStateException(where + ": purposePriority must be a mapping");
        }
        Map<LlmPurpose, List<String>> result = new EnumMap<>(LlmPurpose.class);
        map.forEach((purpose, models) -> {
            LlmPurpose key = enumValue(LlmPurpose.class, String.valueOf(purpose), where, "purposePriority key");
            if (!(models instanceof List<?> list)) {
                throw new IllegalStateException(where + ": purposePriority." + key + " must be a list");
            }
            List<String> ids = new ArrayList<>(list.size());
            for (Object model : list) {
                if (!(model instanceof String id) || id.isBlank()) {
                    // A bare `~` is YAML's null, and these ids start with a tilde — an unquoted
                    // alias arrives here as null rather than as text.
                    throw new IllegalStateException(where + ": purposePriority." + key
                            + " contains a non-string model id (an unquoted '~alias'?)");
                }
                ids.add(id);
            }
            result.put(key, List.copyOf(ids));
        });
        return Map.copyOf(result);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String where, String field) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(where + ": unknown " + field + " '" + value + "'", e);
        }
    }

    private static int intValue(Map<?, ?> map, String key, String where) {
        Object value = map.get(key);
        if (value == null) {
            return 0;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalStateException(where + ": " + key + " must be a number");
        }
        return number.intValue();
    }

    private static String requireText(Map<?, ?> map, String key, String where) {
        String value = text(map, key);
        if (value == null) {
            throw new IllegalStateException(where + ": " + key + " is required");
        }
        return value;
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }
}
