package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.agimate.controlapi.service.seed.ContentLanguage;
import ru.agimate.controlapi.service.seed.SeedContentLocator;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Страховка сидинга: битый frontmatter системного SKILL.md всплывал бы только ERROR-логом на
 * старте приложения (bootstrap глотает исключения по-скиллово) — здесь он валит сборку.
 *
 * <p>Прогоняется по всем языкам: непереведённый скилл иначе обнаружился бы только на инсталляции
 * с этим языком — в виде пресета, у которого пропала часть скилов.
 */
@DisplayName("SystemSkillBootstrap — все системные SKILL.md парсятся во всех языках")
class SystemSkillBootstrapTest {

    static Stream<Arguments> languageAndCode() {
        return Stream.of(ContentLanguage.values()).flatMap(language ->
                SystemSkillBootstrap.SYSTEM_SKILL_CODES.stream().map(code -> Arguments.of(language, code)));
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("languageAndCode")
    @DisplayName("ресурс существует, frontmatter полный, тело непустое")
    void parses(ContentLanguage language, String code) {
        String content = SeedContentLocator.read(SeedContentLocator.Kind.SKILL, code, language);

        SkillFrontmatterParser.ParsedSkill parsed = SkillFrontmatterParser.parse(content);

        assertFalse(parsed.name().isBlank(), "name");
        assertFalse(parsed.description().isBlank(), "description");
        assertFalse(parsed.body().isBlank(), "body");
        assertFalse(parsed.connectors().isEmpty(),
                "connectors: список требуемых коннекторов, пустой — опечатка");
        assertTrue(parsed.connectors().stream().noneMatch(String::isBlank), "blank connector code");
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("languageAndCode")
    @DisplayName("name совпадает с именем папки — по нему пресет резолвит скилл")
    void nameMatchesFolder(ContentLanguage language, String code) {
        SkillFrontmatterParser.ParsedSkill parsed = SkillFrontmatterParser.parse(
                SeedContentLocator.read(SeedContentLocator.Kind.SKILL, code, language));

        assertTrue(code.equals(parsed.name()),
                "папка '" + code + "' и frontmatter name '" + parsed.name() + "' разошлись");
    }
}
