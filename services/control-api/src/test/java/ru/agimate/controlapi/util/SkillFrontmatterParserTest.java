package ru.agimate.controlapi.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("SkillFrontmatterParser: title")
class SkillFrontmatterParserTest {

    @Test
    @DisplayName("title из frontmatter парсится")
    void parsesTitle() {
        String md = """
                ---
                name: my-skill
                title: Мой навык
                description: d
                connectors: [board]
                ---
                body
                """;
        SkillFrontmatterParser.ParsedSkill parsed = SkillFrontmatterParser.parse(md);
        assertEquals("my-skill", parsed.name());
        assertEquals("Мой навык", parsed.title());
    }

    @Test
    @DisplayName("без title — null (фолбэк на name на слое ответа)")
    void titleAbsentIsNull() {
        String md = """
                ---
                name: my-skill
                description: d
                ---
                body
                """;
        SkillFrontmatterParser.ParsedSkill parsed = SkillFrontmatterParser.parse(md);
        assertEquals("my-skill", parsed.name());
        assertNull(parsed.title());
    }
}
