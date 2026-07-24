package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.manage.dto.AgentPresetResponse;
import ru.agimate.controlapi.controller.manage.dto.CreateAgentPresetRequest;
import ru.agimate.controlapi.controller.manage.dto.UpdateAgentPresetRequest;
import ru.agimate.controlapi.database.entities.AgentPreset;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.AgentPresetRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    private static AgentPreset preset(String name, List<String> skillNames) {
        AgentPreset preset = AgentPreset.builder()
                .name(name)
                .title("Личный ассистент")
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
            assertEquals("personal-assistant", response.name());
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
    @DisplayName("admin CRUD")
    class AdminCrud {

        private final UUID adminId = UUID.randomUUID();

        @Test
        @DisplayName("create — валидирует skillNames и сохраняет enabled=true")
        void createsPreset() {
            when(agentPresetRepository.findByName("new-role")).thenReturn(Optional.empty());
            when(skillRepository.findByUserIdAndNameNotDeleted(SYSTEM_USER_ID, "AgiMate Time"))
                    .thenReturn(Optional.of(skill("AgiMate Time", List.of("time"))));
            when(agentPresetRepository.save(any(AgentPreset.class))).thenAnswer(inv -> {
                AgentPreset p = inv.getArgument(0);
                p.setId(UUID.randomUUID());
                return p;
            });

            AgentPresetResponse response = service.create(adminId, new CreateAgentPresetRequest(
                    "new-role", "Роль", "desc", "Инструкции", List.of("AgiMate Time"), 5));

            assertEquals("new-role", response.name());
            assertEquals(5, response.sortOrder());
            assertTrue(response.enabled());
            assertEquals(List.of("AgiMate Time"), response.skillNames());
        }

        @Test
        @DisplayName("create с занятым code → 409")
        void rejectsDuplicateCode() {
            when(agentPresetRepository.findByName("personal-assistant"))
                    .thenReturn(Optional.of(preset("personal-assistant", List.of())));

            assertThrows(ConflictStatusException.class, () -> service.create(adminId,
                    new CreateAgentPresetRequest("personal-assistant", "n", "d", "i", List.of(), 0)));
            verify(agentPresetRepository, never()).save(any());
        }

        @Test
        @DisplayName("create с несуществующим системным скиллом → 400")
        void rejectsUnknownSkill() {
            when(agentPresetRepository.findByName("r")).thenReturn(Optional.empty());
            when(skillRepository.findByUserIdAndNameNotDeleted(SYSTEM_USER_ID, "Ghost"))
                    .thenReturn(Optional.empty());

            BadRequestStatusException ex = assertThrows(BadRequestStatusException.class,
                    () -> service.create(adminId,
                            new CreateAgentPresetRequest("r", "n", "d", "i", List.of("Ghost"), 0)));
            assertTrue(ex.getMessage().contains("Ghost"));
            verify(agentPresetRepository, never()).save(any());
        }

        @Test
        @DisplayName("update — частичное: меняет enabled, code не трогается")
        void updatesPartial() {
            AgentPreset existing = preset("personal-assistant", List.of());
            when(agentPresetRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
            when(agentPresetRepository.save(any(AgentPreset.class))).thenAnswer(inv -> inv.getArgument(0));

            AgentPresetResponse response = service.update(adminId, existing.getId(),
                    new UpdateAgentPresetRequest(null, null, null, null, null, false));

            assertFalse(response.enabled());
            assertEquals("personal-assistant", response.name());
        }

        @Test
        @DisplayName("update несуществующего пресета → 404")
        void updateMissing() {
            UUID id = UUID.randomUUID();
            when(agentPresetRepository.findById(id)).thenReturn(Optional.empty());

            assertThrows(NotFoundStatusException.class, () -> service.update(adminId, id,
                    new UpdateAgentPresetRequest("n", null, null, null, null, null)));
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
                    name: personal-assistant
                    title: Личный ассистент
                    description: Помощник на каждый день
                    skills: [time, persist-memory]
                    ---

                    Ты — личный ассистент.
                    """;

            var parsed = SystemPresetBootstrap.parsePreset(content);

            assertEquals("personal-assistant", parsed.name());
            assertEquals("Личный ассистент", parsed.title());
            assertEquals("Помощник на каждый день", parsed.description());
            assertEquals(List.of("time", "persist-memory"), parsed.skillNames());
            assertEquals(0, parsed.sortOrder());
            assertEquals("Ты — личный ассистент.", parsed.instructions());
        }

        @Test
        @DisplayName("без name или с пустым телом — ошибка")
        void rejectsInvalid() {
            String noName = """
                    ---
                    title: Пресет
                    ---
                    Тело.
                    """;
            String emptyBody = """
                    ---
                    name: p
                    title: Пресет
                    ---
                    """;
            IllegalStateException e1 = assertThrows(IllegalStateException.class,
                    () -> SystemPresetBootstrap.parsePreset(noName));
            assertTrue(e1.getMessage().contains("'name'"));
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
