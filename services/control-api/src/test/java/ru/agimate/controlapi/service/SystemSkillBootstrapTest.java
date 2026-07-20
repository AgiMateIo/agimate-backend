package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Страховка сидинга: битый frontmatter системного SKILL.md всплывал бы только ERROR-логом на
 * старте приложения (bootstrap глотает исключения по-скиллово) — здесь он валит сборку.
 */
@DisplayName("SystemSkillBootstrap — все системные SKILL.md парсятся")
class SystemSkillBootstrapTest {

    static List<String> resources() {
        return SystemSkillBootstrap.SYSTEM_SKILL_RESOURCES;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resources")
    @DisplayName("ресурс существует, frontmatter полный, тело непустое")
    void parses(String resource) throws IOException {
        String content = StreamUtils.copyToString(
                new ClassPathResource(resource).getInputStream(), StandardCharsets.UTF_8);

        SkillFrontmatterParser.ParsedSkill parsed = SkillFrontmatterParser.parse(content);

        assertFalse(parsed.name().isBlank(), "name");
        assertFalse(parsed.description().isBlank(), "description");
        assertFalse(parsed.body().isBlank(), "body");
        assertFalse(parsed.connectors().isEmpty(), "connectors");
        assertTrue(parsed.connectors().stream().noneMatch(String::isBlank), "blank connector code");
    }
}
