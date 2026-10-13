package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.service.seed.SeedContentLocator;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Страховка сидинга: битый frontmatter системного SKILL.md всплывал бы только ERROR-логом на
 * старте приложения (bootstrap глотает исключения по-скиллово) — здесь он валит сборку.
 *
 * <p>Каталоги языков независимы, набор скиллов в них может различаться — каждый язык проверяется по
 * тем кодам, что в нём есть.
 */
@DisplayName("SystemSkillBootstrap — все системные SKILL.md парсятся в своих языках")
class SystemSkillBootstrapTest {

    static Stream<Arguments> languageAndCode() {
        return Stream.of(ContentLanguage.values()).flatMap(language ->
                SystemSkillBootstrap.SYSTEM_SKILL_CODES.stream()
                        .filter(code -> SeedContentLocator.exists(SeedContentLocator.Kind.SKILL, code, language))
                        .map(code -> Arguments.of(language, code)));
    }

    static Stream<String> codes() {
        return SystemSkillBootstrap.SYSTEM_SKILL_CODES.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("codes")
    @DisplayName("код из SYSTEM_SKILL_CODES есть хотя бы в одном языке — иначе это мёртвая запись")
    void codeExistsSomewhere(String code) {
        assertTrue(Stream.of(ContentLanguage.values())
                        .anyMatch(language -> SeedContentLocator.exists(SeedContentLocator.Kind.SKILL, code, language)),
                code + ": нет ни в одной папке seed/skills/<lang>/");
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
        assertTrue(parsed.connectors().stream().noneMatch(r -> r.code().isBlank()), "blank connector code");
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.EnumSource(ContentLanguage.class)
    @DisplayName("каждая папка seed/skills/<lang>/ есть в SYSTEM_SKILL_CODES — иначе скилл молча не сеется")
    void everyFolderIsListed(ContentLanguage language) throws Exception {
        org.springframework.core.io.Resource[] files =
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                        .getResources("classpath*:seed/skills/" + language.dir() + "/*/SKILL.md");
        java.util.Set<String> folders = new java.util.TreeSet<>();
        for (org.springframework.core.io.Resource file : files) {
            String path = file.getURL().getPath();
            String dir = path.substring(0, path.lastIndexOf('/'));
            folders.add(dir.substring(dir.lastIndexOf('/') + 1));
        }

        assertFalse(folders.isEmpty(), "ресурсы не найдены");
        folders.removeAll(SystemSkillBootstrap.SYSTEM_SKILL_CODES);
        assertTrue(folders.isEmpty(), "папки без записи в SYSTEM_SKILL_CODES: " + folders);
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
