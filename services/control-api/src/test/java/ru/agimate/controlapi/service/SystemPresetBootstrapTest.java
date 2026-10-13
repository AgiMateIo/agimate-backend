package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.service.seed.SeedContentLocator;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Страховка сидинга: битый frontmatter системного PRESET.md всплывал бы только ERROR-логом на старте
 * (bootstrap глотает исключения по-пресетно) — здесь он валит сборку. Отдельно проверяем ссылки на
 * скиллы: опечатка в {@code skills:} дала бы пресет без единого коннектора, и заметил бы это только
 * пользователь, у которого агент ничего не умеет.
 *
 * <p>Каталоги языков независимы: набор пресетов в языках может различаться, поэтому каждый язык
 * проверяется сам по себе, а ссылка пресета — на скилл его же языка: так резолвит и сидинг.
 */
@DisplayName("SystemPresetBootstrap — все системные PRESET.md парсятся в своих языках")
class SystemPresetBootstrapTest {

    static Stream<Arguments> languageAndCode() {
        return Stream.of(ContentLanguage.values()).flatMap(language ->
                SystemPresetBootstrap.SYSTEM_PRESET_CODES.stream()
                        .filter(code -> SeedContentLocator.exists(SeedContentLocator.Kind.PRESET, code, language))
                        .map(code -> Arguments.of(language, code)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("codes")
    @DisplayName("код из SYSTEM_PRESET_CODES есть хотя бы в одном языке — иначе это мёртвая запись")
    void codeExistsSomewhere(String code) {
        assertTrue(Stream.of(ContentLanguage.values())
                        .anyMatch(language -> SeedContentLocator.exists(SeedContentLocator.Kind.PRESET, code, language)),
                code + ": нет ни в одной папке seed/presets/<lang>/");
    }

    static Stream<String> codes() {
        return SystemPresetBootstrap.SYSTEM_PRESET_CODES.stream();
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("languageAndCode")
    @DisplayName("ресурс существует, frontmatter полный, тело непустое")
    void parses(ContentLanguage language, String code) {
        SystemPresetBootstrap.ParsedPreset parsed = parse(language, code);

        assertFalse(parsed.name().isBlank(), "name");
        assertFalse(parsed.title().isBlank(), "title");
        assertFalse(parsed.instructions().isBlank(), "instructions");
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("languageAndCode")
    @DisplayName("name совпадает с именем папки — по нему идёт идемпотентный сидинг")
    void nameMatchesFolder(ContentLanguage language, String code) {
        assertEquals(code, parse(language, code).name(),
                "папка и frontmatter name разошлись — сидинг создаст строку не под тем кодом");
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("languageAndCode")
    @DisplayName("каждый упомянутый скилл существует среди системных того же языка")
    void referencesExistingSkills(ContentLanguage language, String code) {
        for (String skill : parse(language, code).skillNames()) {
            assertTrue(SystemSkillBootstrap.SYSTEM_SKILL_CODES.contains(skill)
                            && SeedContentLocator.exists(SeedContentLocator.Kind.SKILL, skill, language),
                    language + "/" + code + " ссылается на скилл '" + skill + "', которого нет в "
                            + SeedContentLocator.path(SeedContentLocator.Kind.SKILL, skill, language));
        }
    }

    private static SystemPresetBootstrap.ParsedPreset parse(ContentLanguage language, String code) {
        return SystemPresetBootstrap.parsePreset(
                SeedContentLocator.read(SeedContentLocator.Kind.PRESET, code, language));
    }
}
