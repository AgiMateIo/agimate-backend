package ru.agimate.controlapi.service.seed;

import ru.agimate.controlapi.database.enums.ContentCategory;

import java.util.Map;

/**
 * Parsed {@code seed/connectors/overrides.yaml}. Absent keys mean the built-in defaults.
 */
public record PresetOverrides(
        Map<String, ContentCategory> categories,
        Map<String, PresetOverride> presets
) {

    public static PresetOverrides empty() {
        return new PresetOverrides(Map.of(), Map.of());
    }

    public PresetOverride preset(String code) {
        return presets.get(code);
    }
}
