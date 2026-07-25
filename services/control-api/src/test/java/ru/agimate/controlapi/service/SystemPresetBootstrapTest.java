package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.agimate.controlapi.service.seed.ContentLanguage;
import ru.agimate.controlapi.service.seed.SeedContentLocator;

import java.util.Set;
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
 * <p>Прогоняется по всем языкам — переведённый (а не скопированный) слаг в {@code skills:} ломает
 * ровно эту связь; см. также {@code SeedContentParityTest}.
 */
@DisplayName("SystemPresetBootstrap — все системные PRESET.md парсятся во всех языках")
class SystemPresetBootstrapTest {

    /** Имена системных скиллов — единственное, на что вправе ссылаться системный пресет. */
    private static final Set<String> SYSTEM_SKILLS = Set.copyOf(SystemSkillBootstrap.SYSTEM_SKILL_CODES);

    static Stream<Arguments> languageAndCode() {
        return Stream.of(ContentLanguage.values()).flatMap(language ->
                SystemPresetBootstrap.SYSTEM_PRESET_CODES.stream().map(code -> Arguments.of(language, code)));
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
    @DisplayName("каждый упомянутый скилл существует среди системных")
    void referencesExistingSkills(ContentLanguage language, String code) {
        for (String skill : parse(language, code).skillNames()) {
            assertTrue(SYSTEM_SKILLS.contains(skill),
                    language + "/" + code + " ссылается на несуществующий скилл '" + skill
                            + "'. Системные: " + SYSTEM_SKILLS);
        }
    }

    private static SystemPresetBootstrap.ParsedPreset parse(ContentLanguage language, String code) {
        return SystemPresetBootstrap.parsePreset(
                SeedContentLocator.read(SeedContentLocator.Kind.PRESET, code, language));
    }
}
