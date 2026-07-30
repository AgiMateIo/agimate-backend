package ru.agimate.controlapi.service.llm.catalog;

import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.enums.MediaTransportType;
import ru.agimate.controlapi.service.seed.SeedYaml;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reader of {@code seed/llm-providers.yaml}; the parsing itself is {@link SeedYaml}.
 */
@UtilityClass
public class LlmCatalogSeed {

    public static final String SEED_PATH = "seed/llm-providers.yaml";

    public static List<LlmCatalogSeedEntry> load() {
        return load(SEED_PATH);
    }

    public static List<LlmCatalogSeedEntry> load(String path) {
        List<Map<?, ?>> raw = SeedYaml.entries(path, "providers");
        List<LlmCatalogSeedEntry> entries = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            entries.add(toEntry(raw.get(i), path + ": providers[" + i + "]"));
        }
        return List.copyOf(entries);
    }

    private static LlmCatalogSeedEntry toEntry(Map<?, ?> map, String position) {
        String code = SeedYaml.requireText(map, "code", position);
        String at = "provider '" + code + "'";
        return new LlmCatalogSeedEntry(
                code,
                SeedYaml.requireText(map, "name", at),
                SeedYaml.requireText(map, "description", at),
                SeedYaml.enumValue(LlmProviderType.class,
                        SeedYaml.requireText(map, "providerType", at), at, "providerType"),
                SeedYaml.text(map, "baseUrl"),
                SeedYaml.enumValue(MediaTransportType.class,
                        SeedYaml.text(map, "mediaTransport"), at, "mediaTransport"),
                SeedYaml.text(map, "apiKeyUrl"),
                Objects.requireNonNullElse(SeedYaml.integer(map, "sortOrder", at), 0),
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
            LlmPurpose key = SeedYaml.enumValue(LlmPurpose.class, String.valueOf(purpose),
                    where, "purposePriority key");
            result.put(key, SeedYaml.stringList(models, where, "purposePriority." + key));
        });
        return Map.copyOf(result);
    }
}
