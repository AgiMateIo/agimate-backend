package ru.agimate.controlapi.service.llm.defaults;

import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.service.seed.SeedYaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reader of {@code seed/llm-models.yaml}; the parsing itself is {@link SeedYaml}.
 */
@UtilityClass
public class LlmModelDefaultsSeed {

    public static final String SEED_PATH = "seed/llm-models.yaml";

    public static List<LlmModelDefaultsSeedEntry> load() {
        return load(SEED_PATH);
    }

    public static List<LlmModelDefaultsSeedEntry> load(String path) {
        List<Map<?, ?>> raw = SeedYaml.entries(path, "models");
        List<LlmModelDefaultsSeedEntry> entries = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> map = raw.get(i);
            String position = path + ": models[" + i + "]";
            String model = SeedYaml.requireText(map, "model", position);
            String at = "model '" + model + "'";
            entries.add(new LlmModelDefaultsSeedEntry(
                    model,
                    SeedYaml.text(map, "displayName"),
                    SeedYaml.integer(map, "contextWindow", at),
                    SeedYaml.integer(map, "maxOutputTokens", at),
                    SeedYaml.strings(map, "inputModalities", at),
                    SeedYaml.strings(map, "outputModalities", at),
                    SeedYaml.strings(map, "supportedParameters", at)));
        }
        return List.copyOf(entries);
    }
}
