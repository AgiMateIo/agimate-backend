package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Страховка сидинга: битый frontmatter системного PRESET.md всплывал бы только ERROR-логом на старте
 * (bootstrap глотает исключения по-пресетно) — здесь он валит сборку. Отдельно проверяем ссылки на
 * скиллы: опечатка в {@code skills:} дала бы пресет без единого коннектора, и заметил бы это только
 * пользователь, у которого агент ничего не умеет.
 */
@DisplayName("SystemPresetBootstrap — все системные PRESET.md парсятся")
class SystemPresetBootstrapTest {

    static List<String> resources() {
        return SystemPresetBootstrap.SYSTEM_PRESET_RESOURCES;
    }

    /** Имена системных скиллов — единственное, на что вправе ссылаться системный пресет. */
    private static final Set<String> SYSTEM_SKILLS = SystemSkillBootstrap.SYSTEM_SKILL_RESOURCES.stream()
            .map(SystemPresetBootstrapTest::read)
            .map(SkillFrontmatterParser::parse)
            .map(SkillFrontmatterParser.ParsedSkill::name)
            .collect(Collectors.toSet());

    @ParameterizedTest(name = "{0}")
    @MethodSource("resources")
    @DisplayName("ресурс существует, frontmatter полный, тело непустое")
    void parses(String resource) {
        SystemPresetBootstrap.ParsedPreset parsed = SystemPresetBootstrap.parsePreset(read(resource));

        assertFalse(parsed.name().isBlank(), "name");
        assertFalse(parsed.title().isBlank(), "title");
        assertFalse(parsed.instructions().isBlank(), "instructions");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resources")
    @DisplayName("каждый упомянутый скилл существует среди системных")
    void referencesExistingSkills(String resource) {
        SystemPresetBootstrap.ParsedPreset parsed = SystemPresetBootstrap.parsePreset(read(resource));

        for (String skill : parsed.skillNames()) {
            assertTrue(SYSTEM_SKILLS.contains(skill),
                    resource + " ссылается на несуществующий скилл '" + skill
                            + "'. Системные: " + SYSTEM_SKILLS);
        }
    }

    private static String read(String resource) {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource(resource).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + resource, e);
        }
    }
}
