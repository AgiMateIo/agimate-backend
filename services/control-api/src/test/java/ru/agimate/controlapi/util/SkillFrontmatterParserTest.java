package ru.agimate.controlapi.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import ru.agimate.controlapi.database.model.ConnectorRequirement.Rule;
import ru.agimate.controlapi.database.model.ConnectorRequirement;
import org.junit.jupiter.api.Nested;

import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.database.enums.Disclosure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Nested
    @DisplayName("connectors — короткая и объектная форма")
    class Connectors {

        private static String md(String connectorsYaml) {
            return """
                    ---
                    name: s
                    connectors:
                    %s
                    ---
                    body
                    """.formatted(connectorsYaml);
        }

        @Test
        @DisplayName("строка — это {code, key = code} без параметров и правил")
        void shortForm() {
            List<ConnectorRequirement> connectors = SkillFrontmatterParser.parse(md("  - mcp\n  - persist-memory")).connectors();

            assertEquals(2, connectors.size());
            assertEquals(new ConnectorRequirement("mcp", "mcp", null, null, null, null), connectors.get(0));
            assertEquals("persist-memory", connectors.get(1).key());
        }

        @Test
        @DisplayName("объект: key, title, params, allow с params элемента, deny у triggers")
        void objectForm() {
            String yaml = """
                      - code: mcp
                        key: context7
                        title: Context7
                        params:
                          url: https://mcp.context7.com/mcp
                        tools:
                          allow: [resolve-library-id, { name: query-docs, params: { limit: 5 } }]
                        triggers:
                          deny: [push]
                    """;
            ConnectorRequirement requirement = SkillFrontmatterParser.parse(md(yaml)).connectors().get(0);

            assertEquals("mcp", requirement.code());
            assertEquals("context7", requirement.key());
            assertEquals("Context7", requirement.title());
            assertEquals(Map.of("url", "https://mcp.context7.com/mcp"), requirement.params());
            assertTrue(requirement.tools().hasAllowList());
            assertEquals(List.of(new Rule("resolve-library-id", null), new Rule("query-docs", Map.of("limit", 5))),
                    requirement.tools().allow());
            assertEquals(List.of("push"), requirement.triggers().deny());
            assertFalse(requirement.triggers().hasAllowList());
        }

        @Test
        @DisplayName("allow и deny вместе → 400")
        void allowXorDeny() {
            String yaml = """
                      - code: mcp
                        tools:
                          allow: [a]
                          deny: [b]
                    """;
            assertThrows(BadRequestStatusException.class, () -> SkillFrontmatterParser.parse(md(yaml)));
        }

        @Test
        @DisplayName("один код дважды без ключей → 400: ключ по умолчанию совпал")
        void duplicateKey() {
            assertThrows(BadRequestStatusException.class,
                    () -> SkillFrontmatterParser.parse(md("  - mcp\n  - code: mcp")));
        }

        @Test
        @DisplayName("объект без code → 400; ключ не kebab-case → 400")
        void codeRequiredKeySlug() {
            assertThrows(BadRequestStatusException.class,
                    () -> SkillFrontmatterParser.parse(md("  - key: docs")));
            assertThrows(BadRequestStatusException.class,
                    () -> SkillFrontmatterParser.parse(md("  - code: mcp\n    key: My Docs")));
        }

        @Test
        @DisplayName("пустые params и пустой title схлопываются в null — одно написание «ничего»")
        void blanksCollapse() {
            ConnectorRequirement requirement = SkillFrontmatterParser.parse(
                    md("  - code: mcp\n    title: '  '\n    params: {}")).connectors().get(0);

            assertNull(requirement.title());
            assertNull(requirement.params());
        }
    }
}
