package ru.agimate.controlapi.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.database.enums.Disclosure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("SkillFrontmatterParser")
class SkillFrontmatterParserTest {

    @Test
    @DisplayName("disclosure: lazy читается без учёта регистра, без поля — EAGER, мусор — 400")
    void disclosure() {
        String lazy = """
                ---
                name: s
                disclosure: Lazy
                ---
                body
                """;
        String absent = """
                ---
                name: s
                ---
                body
                """;
        String junk = """
                ---
                name: s
                disclosure: maybe
                ---
                body
                """;
        assertEquals(Disclosure.LAZY, SkillFrontmatterParser.parse(lazy).disclosure());
        assertEquals(Disclosure.EAGER, SkillFrontmatterParser.parse(absent).disclosure());
        assertThrows(BadRequestStatusException.class, () -> SkillFrontmatterParser.parse(junk));
    }

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
