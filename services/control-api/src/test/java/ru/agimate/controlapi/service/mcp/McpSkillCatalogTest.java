package ru.agimate.controlapi.service.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.agimate.controlapi.controller.agent.dto.AgentSkillWithConnectorsResponse;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.enums.Disclosure;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.service.runcontext.RunCatalog;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("McpSkillCatalog — навыки агента как Agent Skills")
class McpSkillCatalogTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    @Mock
    private RunCatalog runCatalog;
    @Mock
    private SkillRepository skillRepository;

    @InjectMocks
    private McpSkillCatalog catalog;

    private static AgentSkillWithConnectorsResponse bound(UUID id, String name, String description) {
        return new AgentSkillWithConnectorsResponse(id, name, description, List.of("board"), Disclosure.LAZY);
    }

    private static Skill skill(UUID id, String name, String title, String body) {
        return Skill.builder().id(id).name(name).title(title).mdContent(body).build();
    }

    private static String digest(String content) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("навык становится записью с манифестом и детерминированным SKILL.md")
    void buildsEntry() {
        UUID skillId = UUID.randomUUID();
        when(runCatalog.skillsForAgent(AGENT_ID))
                .thenReturn(List.of(bound(skillId, "board-workflow", "Ведёт доску.")));
        when(skillRepository.findAllById(any()))
                .thenReturn(List.of(skill(skillId, "board-workflow", null, "Handle the board.")));

        McpSkillCatalog.Page page = catalog.forAgent(AGENT_ID, 0);
        McpSkillCatalog.Entry entry = page.entries().get(0);

        String expected = """
                ---
                name: "board-workflow"
                description: "Ведёт доску."
                ---

                Handle the board.
                """;
        assertEquals(expected, entry.content());
        assertEquals("skill://board-workflow/SKILL.md", entry.skill().uri());
        assertEquals(Map.of("name", "board-workflow", "description", "Ведёт доску."),
                entry.skill().frontmatter());
        assertEquals(1, entry.skill().resources().size());
        assertEquals(digest(expected), entry.skill().resources().get(0).digest());
        assertEquals(expected.getBytes(StandardCharsets.UTF_8).length,
                entry.skill().resources().get(0).size());
        assertNull(page.nextCursor());
        assertTrue(catalog.find(AGENT_ID, "skill://board-workflow/SKILL.md").isPresent());
    }

    @Test
    @DisplayName("одинаковые имена внутри одного сервера разводятся суффиксом, оба навыка достижимы")
    void disambiguatesNames() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(runCatalog.skillsForAgent(AGENT_ID)).thenReturn(List.of(
                bound(first, "review", "First"), bound(second, "review", "Second")));
        when(skillRepository.findAllById(any())).thenReturn(List.of(
                skill(first, "review", null, "first body"), skill(second, "review", null, "second body")));

        McpSkillCatalog.Page page = catalog.forAgent(AGENT_ID, 0);

        assertEquals(List.of("skill://review/SKILL.md", "skill://review-2/SKILL.md"),
                page.entries().stream().map(e -> e.skill().uri()).toList());
        assertTrue(catalog.find(AGENT_ID, "skill://review-2/SKILL.md").isPresent());
    }

    @Test
    @DisplayName("длинное имя усекается и не заканчивается дефисом")
    void truncatesLongNames() {
        UUID skillId = UUID.randomUUID();
        String longName = "a".repeat(70);
        when(runCatalog.skillsForAgent(AGENT_ID)).thenReturn(List.of(bound(skillId, longName, "d")));
        when(skillRepository.findAllById(any()))
                .thenReturn(List.of(skill(skillId, longName, null, "body")));

        String name = (String) catalog.forAgent(AGENT_ID, 0).entries().get(0)
                .skill().frontmatter().get("name");

        assertTrue(name.length() <= 64, name);
        assertFalse(name.endsWith("-"), name);
        assertTrue(name.matches("[a-z0-9]+(-[a-z0-9]+)*"), name);
    }

    @Test
    @DisplayName("пустое описание падает на title, длинное обрезается до 1024 code points")
    void fallsBackAndTruncatesDescription() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(runCatalog.skillsForAgent(AGENT_ID)).thenReturn(List.of(
                bound(first, "one", null), bound(second, "two", "x".repeat(1200))));
        when(skillRepository.findAllById(any())).thenReturn(List.of(
                skill(first, "one", "First title", "a"), skill(second, "two", null, "b")));

        List<McpSkillCatalog.Entry> entries = catalog.forAgent(AGENT_ID, 0).entries();

        assertEquals("First title", entries.get(0).skill().frontmatter().get("description"));
        String description = (String) entries.get(1).skill().frontmatter().get("description");
        assertEquals(1024, description.codePointCount(0, description.length()));
    }

    @Test
    @DisplayName("удалённая строка навыка в листинг не попадает")
    void skipsDeletedSkill() {
        UUID skillId = UUID.randomUUID();
        when(runCatalog.skillsForAgent(AGENT_ID)).thenReturn(List.of(bound(skillId, "gone", "d")));
        Skill deleted = skill(skillId, "gone", null, "body");
        deleted.setDeletedAt(LocalDateTime.now());
        when(skillRepository.findAllById(any())).thenReturn(List.of(deleted));

        assertTrue(catalog.forAgent(AGENT_ID, 0).entries().isEmpty());
    }

    @Test
    @DisplayName("неизвестный URI не резолвится")
    void unknownUri() {
        when(runCatalog.skillsForAgent(AGENT_ID)).thenReturn(List.of());
        when(skillRepository.findAllById(any())).thenReturn(List.of());

        assertTrue(catalog.find(AGENT_ID, "skill://nope/SKILL.md").isEmpty());
    }

    @Test
    @DisplayName("name в YAML остаётся строкой для числовых, булевых и date-подобных имён")
    void nameStaysStringInYaml() {
        for (String raw : List.of("2024", "2024-01-01", "true", "null", "0x1f")) {
            UUID skillId = UUID.randomUUID();
            when(runCatalog.skillsForAgent(AGENT_ID)).thenReturn(List.of(bound(skillId, raw, "d")));
            when(skillRepository.findAllById(any()))
                    .thenReturn(List.of(skill(skillId, raw, null, "body")));

            String content = catalog.forAgent(AGENT_ID, 0).entries().get(0).content();
            Object name = SkillFrontmatterParser.parseRaw(content, "SKILL.md").fields().get("name");

            assertInstanceOf(String.class, name, raw);
            assertEquals(raw, name, raw);
        }
    }

    @Test
    @DisplayName("непечатаемые символы описания переживают YAML как данные")
    void escapesNonPrintables() {
        String description = "line\u007f and \u0085 next end";
        UUID skillId = UUID.randomUUID();
        when(runCatalog.skillsForAgent(AGENT_ID))
                .thenReturn(List.of(bound(skillId, "board-workflow", description)));
        when(skillRepository.findAllById(any()))
                .thenReturn(List.of(skill(skillId, "board-workflow", null, "body")));

        String content = catalog.forAgent(AGENT_ID, 0).entries().get(0).content();

        assertEquals(description, SkillFrontmatterParser.parseRaw(content, "SKILL.md")
                .fields().get("description"));
    }

    @Test
    @DisplayName("усечение длинного имени не оставляет двойной дефис")
    void truncationDoesNotLeaveDoubleHyphen() {
        UUID skillId = UUID.randomUUID();
        String longName = "a".repeat(54) + "-" + "b".repeat(20);
        when(runCatalog.skillsForAgent(AGENT_ID)).thenReturn(List.of(bound(skillId, longName, "d")));
        when(skillRepository.findAllById(any()))
                .thenReturn(List.of(skill(skillId, longName, null, "body")));

        String name = (String) catalog.forAgent(AGENT_ID, 0).entries().get(0)
                .skill().frontmatter().get("name");

        assertTrue(name.matches("[a-z0-9]+(-[a-z0-9]+)*"), name);
        assertFalse(name.contains("--"), name);
    }

    @Test
    @DisplayName("суффикс при совпадении не зависит от порядка строк репозитория")
    void uriAssignmentIsStableAcrossListOrder() {
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(skillRepository.findAllById(any())).thenReturn(List.of(
                skill(low, "review", null, "low body"),
                skill(high, "review", null, "high body")));

        when(runCatalog.skillsForAgent(AGENT_ID)).thenReturn(List.of(
                bound(high, "review", "high"), bound(low, "review", "low")));
        List<McpSkillCatalog.Entry> first = catalog.forAgent(AGENT_ID, 0).entries();

        when(runCatalog.skillsForAgent(AGENT_ID)).thenReturn(List.of(
                bound(low, "review", "low"), bound(high, "review", "high")));
        List<McpSkillCatalog.Entry> second = catalog.forAgent(AGENT_ID, 0).entries();

        assertEquals(first.get(0).content(), second.get(0).content());
        assertTrue(first.get(0).content().contains("low body"));
    }

    @Test
    @DisplayName("страница ограничена сотней, nextCursor — следующее смещение")
    void paginates() {
        List<AgentSkillWithConnectorsResponse> bound = new java.util.ArrayList<>();
        List<Skill> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) {
            UUID id = UUID.randomUUID();
            bound.add(bound(id, "skill-" + i, "d"));
            rows.add(skill(id, "skill-" + i, null, "body"));
        }
        when(runCatalog.skillsForAgent(AGENT_ID)).thenReturn(bound);
        when(skillRepository.findAllById(any())).thenReturn(rows);

        McpSkillCatalog.Page first = catalog.forAgent(AGENT_ID, 0);
        McpSkillCatalog.Page second = catalog.forAgent(AGENT_ID, 100);

        assertEquals(100, first.entries().size());
        assertEquals("100", first.nextCursor());
        assertEquals(1, second.entries().size());
        assertNull(second.nextCursor());
    }
}
