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
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.manage.dto.CreateSkillRequest;
import ru.agimate.controlapi.controller.manage.dto.SkillResponse;
import ru.agimate.controlapi.controller.manage.dto.UpdateSkillRequest;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.AgentPresetRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.agimate.controlapi.service.SystemSkillBootstrap.SYSTEM_USER_ID;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkillService")
class SkillServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SKILL_ID = UUID.randomUUID();

    @Mock
    private SkillRepository skillRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private AgentSkillRepository agentSkillRepository;
    @Mock
    private AgentPresetRepository agentPresetRepository;
    @Mock
    private ConnectorRepository connectorRepository;

    @InjectMocks
    private SkillService service;

    @Nested
    @DisplayName("create — валидация connector_codes")
    class Create {

        private void knownConnectors(String... codes) {
            List<String> known = List.of(codes);
            when(connectorRepository.existsById(any(String.class)))
                    .thenAnswer(inv -> known.contains(inv.<String>getArgument(0)));
        }

        private String skillMd(String connectorsYaml) {
            return """
                    ---
                    name: test-skill
                    description: d
                    connectors:
                    %s
                    ---
                    Body.
                    """.formatted(connectorsYaml);
        }

        @Test
        @DisplayName("неизвестный код коннектора → 400 с перечислением")
        void rejectsUnknownConnectorCode() {
            knownConnectors("board");
            CreateSkillRequest request = new CreateSkillRequest(skillMd("  - board\n  - bogus"), false);

            BadRequestStatusException ex = assertThrows(BadRequestStatusException.class,
                    () -> service.create(USER_ID, request));
            assertTrue(ex.getMessage().contains("bogus"), ex.getMessage());
        }

        @Test
        @DisplayName("все коды известны → скилл создаётся")
        void acceptsKnownConnectorCodes() {
            knownConnectors("board");
            when(skillRepository.existsByUserIdAndNameNotDeleted(USER_ID, "test-skill")).thenReturn(false);
            when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> {
                Skill s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                return s;
            });
            CreateSkillRequest request = new CreateSkillRequest(skillMd("  - board"), false);

            SkillResponse response = service.create(USER_ID, request);

            assertEquals("test-skill", response.name());
            assertEquals(List.of("board"), response.connectorCodes());
        }
    }

    @Nested
    @DisplayName("delete — soft-delete скилла + чистка agent_skills")
    class Delete {

        @Test
        @DisplayName("удаляет привязки ко всем агентам вместе со скиллом")
        void cleansAgentSkills() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(
                    Skill.builder().id(SKILL_ID).userId(USER_ID).name("test-skill").build()));
            when(agentSkillRepository.deleteBySkillId(SKILL_ID)).thenReturn(2);

            service.delete(SKILL_ID, USER_ID, false);

            verify(skillRepository).softDelete(eq(SKILL_ID), any());
            verify(agentSkillRepository).deleteBySkillId(SKILL_ID);
        }

        @Test
        @DisplayName("чужой скилл → Forbidden, привязки не тронуты")
        void foreignSkillForbidden() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(
                    Skill.builder().id(SKILL_ID).userId(UUID.randomUUID()).name("test-skill").build()));

            assertThrows(ForbiddenStatusException.class, () -> service.delete(SKILL_ID, USER_ID, false));

            verifyNoInteractions(agentSkillRepository);
            verify(skillRepository, never()).softDelete(any(), any());
        }

        @Test
        @DisplayName("скилл не найден/уже удалён → NotFound")
        void missingSkillNotFound() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundStatusException.class, () -> service.delete(SKILL_ID, USER_ID, false));

            verifyNoInteractions(agentSkillRepository);
        }
    }

    @Nested
    @DisplayName("системные скилы — доступ и guardrails ADMIN")
    class SystemSkills {

        private Skill systemSkill() {
            return Skill.builder().id(SKILL_ID).userId(SYSTEM_USER_ID).name("board")
                    .isPublic(true).version(1).connectorCodes(new java.util.ArrayList<>(List.of("board")))
                    .mdContent("body").description("d").build();
        }

        @Test
        @DisplayName("не-админ не может править системный скилл → Forbidden")
        void nonAdminCannotEditSystem() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(systemSkill()));

            assertThrows(ForbiddenStatusException.class,
                    () -> service.update(SKILL_ID, USER_ID, false,
                            new UpdateSkillRequest(systemSkillMd("board"), true)));
        }

        @Test
        @DisplayName("админ правит тело системного скилла → version++")
        void adminEditsSystemBumpsVersion() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(systemSkill()));
            when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> inv.getArgument(0));

            SkillResponse response = service.update(SKILL_ID, USER_ID, true,
                    new UpdateSkillRequest(systemSkillMd("board"), true));

            assertEquals(2, response.version());
            assertTrue(response.system());
        }

        @Test
        @DisplayName("переименование системного скилла запрещено → 400")
        void renameSystemForbidden() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(systemSkill()));

            assertThrows(BadRequestStatusException.class,
                    () -> service.update(SKILL_ID, USER_ID, true,
                            new UpdateSkillRequest(systemSkillMd("renamed"), true)));
        }

        @Test
        @DisplayName("delete системного скилла с привязками → 409, не удаляется")
        void deleteBoundSystemConflict() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(systemSkill()));
            when(agentSkillRepository.existsBySkillId(SKILL_ID)).thenReturn(true);

            assertThrows(ConflictStatusException.class, () -> service.delete(SKILL_ID, USER_ID, true));

            verify(skillRepository, never()).softDelete(any(), any());
        }

        @Test
        @DisplayName("delete системного скилла с ссылкой из пресета → 409")
        void deletePresetReferencedSystemConflict() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(systemSkill()));
            when(agentSkillRepository.existsBySkillId(SKILL_ID)).thenReturn(false);
            when(agentPresetRepository.existsBySkillNameReferenced("board")).thenReturn(true);

            assertThrows(ConflictStatusException.class, () -> service.delete(SKILL_ID, USER_ID, true));

            verify(skillRepository, never()).softDelete(any(), any());
        }

        @Test
        @DisplayName("createSystem — owner=SYSTEM, всегда public")
        void createSystemForcesOwnerAndPublic() {
            when(skillRepository.existsByUserIdAndNameNotDeleted(SYSTEM_USER_ID, "new")).thenReturn(false);
            when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> {
                Skill s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                return s;
            });

            SkillResponse response = service.createSystem(new CreateSkillRequest(systemSkillMd("new"), false));

            assertTrue(response.isPublic());
            assertTrue(response.system());
            assertEquals(SYSTEM_USER_ID, response.userId());
        }

        private String systemSkillMd(String name) {
            return """
                    ---
                    name: %s
                    description: d
                    ---
                    Body.
                    """.formatted(name);
        }
    }
}
