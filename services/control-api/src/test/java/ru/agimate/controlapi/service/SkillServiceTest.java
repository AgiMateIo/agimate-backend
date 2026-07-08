package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.controller.manage.dto.CreateSkillRequest;
import ru.agimate.controlapi.controller.manage.dto.SkillResponse;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
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
    private ConnectorRegistry connectorRegistry;

    @InjectMocks
    private SkillService service;

    @Nested
    @DisplayName("create — валидация connector_codes")
    class Create {

        private void knownConnectors(String... codes) {
            List<ConnectorHandler> handlers = java.util.Arrays.stream(codes).map(code -> {
                ConnectorHandler handler = mock(ConnectorHandler.class);
                when(handler.connectorCode()).thenReturn(code);
                return handler;
            }).toList();
            when(connectorRegistry.getHandlers()).thenReturn(List.copyOf(handlers));
        }

        private String skillMd(String connectorsYaml) {
            return """
                    ---
                    name: Test Skill
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
            when(skillRepository.existsByUserIdAndNameNotDeleted(USER_ID, "Test Skill")).thenReturn(false);
            when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> {
                Skill s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                return s;
            });
            CreateSkillRequest request = new CreateSkillRequest(skillMd("  - board"), false);

            SkillResponse response = service.create(USER_ID, request);

            assertEquals("Test Skill", response.name());
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
                    Skill.builder().id(SKILL_ID).userId(USER_ID).name("Test Skill").build()));
            when(agentSkillRepository.deleteBySkillId(SKILL_ID)).thenReturn(2);

            service.delete(SKILL_ID, USER_ID);

            verify(skillRepository).softDelete(eq(SKILL_ID), any());
            verify(agentSkillRepository).deleteBySkillId(SKILL_ID);
        }

        @Test
        @DisplayName("чужой скилл → Forbidden, привязки не тронуты")
        void foreignSkillForbidden() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(
                    Skill.builder().id(SKILL_ID).userId(UUID.randomUUID()).name("Test Skill").build()));

            assertThrows(ForbiddenStatusException.class, () -> service.delete(SKILL_ID, USER_ID));

            verifyNoInteractions(agentSkillRepository);
            verify(skillRepository, never()).softDelete(any(), any());
        }

        @Test
        @DisplayName("скилл не найден/уже удалён → NotFound")
        void missingSkillNotFound() {
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundStatusException.class, () -> service.delete(SKILL_ID, USER_ID));

            verifyNoInteractions(agentSkillRepository);
        }
    }
}
