package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.controller.manage.dto.AgentPresetResponse;
import ru.agimate.controlapi.database.entities.AgentPreset;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.AgentPresetRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static ru.agimate.controlapi.service.SystemSkillBootstrap.SYSTEM_USER_ID;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentPresetService")
class AgentPresetServiceTest {

    @Mock
    private AgentPresetRepository agentPresetRepository;
    @Mock
    private SkillRepository skillRepository;

    @InjectMocks
    private AgentPresetService service;

    private static AgentPreset preset(String code, List<String> skillNames) {
        AgentPreset preset = AgentPreset.builder()
                .code(code)
                .name("Личный ассистент")
                .description("desc")
                .instructions("Ты — ассистент.")
                .skillNames(skillNames)
                .build();
        preset.setId(UUID.randomUUID());
        return preset;
    }

    private static Skill skill(String name, List<String> connectors) {
        Skill skill = Skill.builder()
                .name(name)
                .description(name + " desc")
                .mdContent("body")
                .connectorCodes(connectors)
                .userId(SYSTEM_USER_ID)
                .isPublic(true)
                .build();
        skill.setId(UUID.randomUUID());
        return skill;
    }

    @Nested
    @DisplayName("list")
    class ListPresets {

        @Test
        @DisplayName("резолвит скилы по имени и собирает union connectorCodes")
        void resolvesSkillsAndConnectors() {
            when(agentPresetRepository.findAllByEnabledTrueOrderBySortOrderAscNameAsc())
                    .thenReturn(List.of(preset("personal-assistant", List.of("AgiMate Time", "AgiMate Memory"))));
            Skill time = skill("AgiMate Time", List.of("time"));
            Skill memory = skill("AgiMate Memory", List.of("persist-memory"));
            when(skillRepository.findByUserIdAndNameNotDeleted(SYSTEM_USER_ID, "AgiMate Time"))
                    .thenReturn(Optional.of(time));
            when(skillRepository.findByUserIdAndNameNotDeleted(SYSTEM_USER_ID, "AgiMate Memory"))
                    .thenReturn(Optional.of(memory));

            List<AgentPresetResponse> presets = service.list();

            assertEquals(1, presets.size());
            AgentPresetResponse response = presets.get(0);
            assertEquals("personal-assistant", response.code());
            assertEquals("Ты — ассистент.", response.instructions());
            assertEquals(List.of(time.getId(), memory.getId()),
                    response.skills().stream().map(AgentPresetResponse.PresetSkill::id).toList());
            assertEquals(List.of("time", "persist-memory"), response.connectorCodes());
        }

        @Test
        @DisplayName("исчезнувший системный скилл выпадает из ответа, листинг не падает")
        void skipsMissingSkill() {
            when(agentPresetRepository.findAllByEnabledTrueOrderBySortOrderAscNameAsc())
                    .thenReturn(List.of(preset("personal-assistant", List.of("Gone", "AgiMate Time"))));
            when(skillRepository.findByUserIdAndNameNotDeleted(SYSTEM_USER_ID, "Gone"))
                    .thenReturn(Optional.empty());
            when(skillRepository.findByUserIdAndNameNotDeleted(SYSTEM_USER_ID, "AgiMate Time"))
                    .thenReturn(Optional.of(skill("AgiMate Time", List.of("time"))));

            AgentPresetResponse response = service.list().get(0);

            assertEquals(1, response.skills().size());
            assertEquals("AgiMate Time", response.skills().get(0).name());
            assertEquals(List.of("time"), response.connectorCodes());
        }

        @Test
        @DisplayName("дублирующиеся connectorCodes схлопываются")
        void deduplicatesConnectorCodes() {
            when(agentPresetRepository.findAllByEnabledTrueOrderBySortOrderAscNameAsc())
                    .thenReturn(List.of(preset("p", List.of("A", "B"))));
            when(skillRepository.findByUserIdAndNameNotDeleted(SYSTEM_USER_ID, "A"))
                    .thenReturn(Optional.of(skill("A", List.of("time", "board"))));
            when(skillRepository.findByUserIdAndNameNotDeleted(SYSTEM_USER_ID, "B"))
                    .thenReturn(Optional.of(skill("B", List.of("board"))));

            assertEquals(List.of("time", "board"), service.list().get(0).connectorCodes());
        }
    }

    @Nested
    @DisplayName("parsePreset (PRESET.md)")
    class ParsePreset {

        @Test
        @DisplayName("разбирает frontmatter и тело-инструкции")
        void parsesPresetMd() {
            String content = """
                    ---
                    code: personal-assistant
                    name: Личный ассистент
                    description: Помощник на каждый день
                    skills: [AgiMate Time, AgiMate Memory]
                    ---

                    Ты — личный ассистент.
                    """;

            var parsed = SystemPresetBootstrap.parsePreset(content);

            assertEquals("personal-assistant", parsed.code());
            assertEquals("Личный ассистент", parsed.name());
            assertEquals("Помощник на каждый день", parsed.description());
            assertEquals(List.of("AgiMate Time", "AgiMate Memory"), parsed.skillNames());
            assertEquals(0, parsed.sortOrder());
            assertEquals("Ты — личный ассистент.", parsed.instructions());
        }

        @Test
        @DisplayName("без code или с пустым телом — ошибка")
        void rejectsInvalid() {
            String noCode = """
                    ---
                    name: Пресет
                    ---
                    Тело.
                    """;
            String emptyBody = """
                    ---
                    code: p
                    name: Пресет
                    ---
                    """;
            IllegalStateException e1 = assertThrows(IllegalStateException.class,
                    () -> SystemPresetBootstrap.parsePreset(noCode));
            assertTrue(e1.getMessage().contains("'code'"));
            assertThrows(IllegalStateException.class, () -> SystemPresetBootstrap.parsePreset(emptyBody));
        }

        @Test
        @DisplayName("PRESET.md без frontmatter — ошибка парсера")
        void rejectsMissingFrontmatter() {
            assertThrows(BadRequestStatusException.class,
                    () -> SystemPresetBootstrap.parsePreset("Просто текст без frontmatter"));
        }
    }
}
