package ru.agimate.controlapi.service.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.database.enums.ConnectorPresetAuth;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PresetOverrides — строгая проверка overrides.yaml")
class PresetOverridesParsingTest {

    @Test
    @DisplayName("неизвестный корневой ключ — ошибка")
    void unknownRootKey() {
        assertThrows(IllegalStateException.class,
                () -> ConnectorPresetSeedReader.parseOverrides(Map.of("preset", Map.of())));
    }

    @Test
    @DisplayName("неизвестный ключ пресета — ошибка, а не молчаливое игнорирование")
    void unknownPresetKey() {
        assertThrows(IllegalStateException.class,
                () -> ConnectorPresetSeedReader.parseOverrides(Map.of("presets",
                        Map.of("square", Map.of("headerfields", List.of())))));
    }

    @Test
    @DisplayName("required не boolean — ошибка")
    void nonBooleanRequired() {
        assertThrows(IllegalStateException.class,
                () -> ConnectorPresetSeedReader.parseOverrideFields(
                        Map.of("auth_token", Map.of("required", "yes")), "square"));
    }

    @Test
    @DisplayName("неизвестный ключ заголовка — ошибка")
    void unknownHeaderKey() {
        assertThrows(IllegalStateException.class,
                () -> ConnectorPresetSeedReader.parseOverrideHeaders(
                        List.of(Map.of("name", "X-API-Token", "lable", "typo")), "square"));
    }

    @Test
    @DisplayName("override для несуществующего кода — ошибка")
    void unknownOverrideCode() {
        PresetOverrides overrides = new PresetOverrides(Map.of(), Map.of("square",
                new PresetOverride(List.of(ConnectorPresetAuth.TOKEN), null, null)));

        assertThrows(IllegalStateException.class,
                () -> ConnectorPresetSeedReader.validateOverrideCodes(Set.of("bitrix24"), overrides));
    }

    @Test
    @DisplayName("overrides.yaml из ресурсов читается даже без JSON-файлов")
    void readsOverridesResource() {
        assertNotNull(ConnectorPresetSeedReader.readOverrides());
    }

    @Test
    @DisplayName("валидный override разбирается в authMethods и fields")
    void validOverride() {
        PresetOverrides overrides = ConnectorPresetSeedReader.parseOverrides(Map.of("presets",
                Map.of("square", Map.of(
                        "authMethods", List.of("TOKEN"),
                        "fields", Map.of("auth_token", Map.of("label", "Square token", "required", true))))));

        PresetOverride preset = overrides.preset("square");
        assertEquals(List.of(ConnectorPresetAuth.TOKEN), preset.authMethods());
        assertEquals("Square token", preset.fields().get("auth_token").label());
        assertTrue(preset.fields().get("auth_token").required());
    }
}
