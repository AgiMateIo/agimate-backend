package ru.agimate.controlapi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.controller.manage.dto.AgentSkillResponse;
import ru.agimate.controlapi.controller.manage.dto.SkillConnectorStatus;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.AgentSkillConnection;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillConnectionRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;
import ru.agimate.controlapi.service.connection.ConnectionBindingService.ConnectorKind;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentSkillService — какой инстанс имеет в виду навык")
class AgentSkillServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SKILL_ID = UUID.randomUUID();
    private static final UUID AGENT_SKILL_ID = UUID.randomUUID();
    private static final UUID TELEGRAM_ID = UUID.randomUUID();
    private static final UUID MEMORY_MODE_ID = UUID.randomUUID();

    @Mock
    private AgentSkillRepository agentSkillRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private SkillRepository skillRepository;
    @Mock
    private ConnectionRepository connectionRepository;
    @Mock
    private AgentSkillPolicyService agentSkillPolicyService;
    @Mock
    private AgentSkillConnectionRepository agentSkillConnectionRepository;
    @Mock
    private ConnectionBindingService connectionBindingService;

    @InjectMocks
    private AgentSkillService service;

    private static Connection connection(UUID id, String code, String name) {
        return Connection.builder().id(id).userId(USER_ID).connectorCode(code)
                .fullCode(code).name(name).enabled(true).build();
    }

    private final Connection telegram = connection(TELEGRAM_ID, "telegram", "Рабочий");
    private final Connection memory = connection(MEMORY_MODE_ID, "persist-memory", null);

    @BeforeEach
    void setUp() {
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(
                Agent.builder().id(AGENT_ID).userId(USER_ID).name("agent").build()));
        when(agentSkillRepository.save(any(AgentSkill.class))).thenAnswer(invocation -> {
            AgentSkill saved = invocation.getArgument(0);
            saved.setId(AGENT_SKILL_ID);
            return saved;
        });
        when(connectionBindingService.kindOf("telegram")).thenReturn(ConnectorKind.EXTERNAL);
        when(connectionBindingService.kindOf("persist-memory")).thenReturn(ConnectorKind.INTERNAL);
        when(connectionBindingService.kindOf("ghost")).thenReturn(ConnectorKind.UNKNOWN);
        when(connectionBindingService.ensureModeConnection(USER_ID, "persist-memory")).thenReturn(memory);
        when(connectionRepository.findByIdAndUserIdNotDeleted(TELEGRAM_ID, USER_ID))
                .thenReturn(Optional.of(telegram));
        when(agentSkillConnectionRepository.findByAgentSkillIdIn(anyList())).thenReturn(List.of());
        when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of());
    }

    private void skill(String... connectorCodes) {
        Skill skill = Skill.builder()
                .id(SKILL_ID).userId(USER_ID).name("skill").version(1)
                .connectorCodes(List.of(connectorCodes))
                .build();
        when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(skill));
    }

    private List<AgentSkillConnection> savedLinks() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentSkillConnection>> captor = ArgumentCaptor.forClass(List.class);
        verify(agentSkillConnectionRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("привязка навыка")
    class Binding {

        @Test
        @DisplayName("внешний коннектор без ссылки → 400, выбирать инстанс обязан пользователь")
        void externalRequiresChoice() {
            skill("telegram");

            assertThrows(BadRequestStatusException.class,
                    () -> service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of()));
        }

        @Test
        @DisplayName("ссылка на инстанс чужого коннектора → 400")
        void externalMustMatchTheCode() {
            skill("telegram");
            UUID gmailId = UUID.randomUUID();
            when(connectionRepository.findByIdAndUserIdNotDeleted(gmailId, USER_ID))
                    .thenReturn(Optional.of(connection(gmailId, "gmail", "Почта")));

            assertThrows(BadRequestStatusException.class,
                    () -> service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of("telegram", gmailId)));
        }

        @Test
        @DisplayName("код, который навык не объявлял → 400")
        void rejectsUndeclaredCode() {
            skill("telegram");

            assertThrows(BadRequestStatusException.class, () -> service.create(
                    AGENT_ID, SKILL_ID, USER_ID, Map.of("telegram", TELEGRAM_ID, "gmail", UUID.randomUUID())));
        }

        @Test
        @DisplayName("внутренний коннектор — ссылку ставит сервер, id от клиента не нужен")
        void internalIsResolvedByTheServer() {
            skill("persist-memory");

            service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of());

            List<AgentSkillConnection> links = savedLinks();
            assertEquals(1, links.size());
            assertEquals("persist-memory", links.get(0).getConnectorCode());
            assertEquals(MEMORY_MODE_ID, links.get(0).getConnectionId());
        }

        @Test
        @DisplayName("внутренний коннектор с чужим id → 400, а не тихая подмена")
        void internalRejectsAWrongId() {
            skill("persist-memory");

            assertThrows(BadRequestStatusException.class, () -> service.create(
                    AGENT_ID, SKILL_ID, USER_ID, Map.of("persist-memory", UUID.randomUUID())));
        }

        @Test
        @DisplayName("неизвестный коннектор навык не ломает — строки нет, статус неудовлетворён")
        void unknownConnectorIsSkipped() {
            skill("ghost");

            AgentSkillResponse response = service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of());

            assertTrue(savedLinks().isEmpty());
            assertFalse(response.satisfied());
            assertNull(response.connectors().get(0).connectionId());
        }
    }

    @Nested
    @DisplayName("удовлетворённость")
    class Satisfaction {

        private void bound(Connection... connections) {
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of(connections));
        }

        private void referenced(String code, UUID connectionId) {
            when(agentSkillConnectionRepository.findByAgentSkillIdIn(anyList())).thenReturn(List.of(
                    AgentSkillConnection.builder()
                            .agentSkillId(AGENT_SKILL_ID).connectorCode(code).connectionId(connectionId).build()));
        }

        @Test
        @DisplayName("ссылка есть и инстанс привязан → удовлетворён, видно имя инстанса")
        void referencedAndBound() {
            skill("telegram");
            bound(telegram);
            referenced("telegram", TELEGRAM_ID);

            AgentSkillResponse response = service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of("telegram", TELEGRAM_ID));

            SkillConnectorStatus status = response.connectors().get(0);
            assertTrue(status.satisfied());
            assertEquals(TELEGRAM_ID, status.connectionId());
            assertEquals("Рабочий", status.connectionName());
            assertTrue(response.satisfied());
        }

        @Test
        @DisplayName("ссылка есть, но инстанс не привязан → не удовлетворён, инстанс всё равно показан")
        void referencedButNotBound() {
            skill("telegram");
            bound();
            referenced("telegram", TELEGRAM_ID);
            when(connectionRepository.findByIdInNotDeleted(anyList())).thenReturn(List.of(telegram));

            AgentSkillResponse response = service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of("telegram", TELEGRAM_ID));

            SkillConnectorStatus status = response.connectors().get(0);
            assertFalse(status.satisfied(), "выбран, но агенту не открыт");
            assertEquals(TELEGRAM_ID, status.connectionId(), "показываем, что именно выбрано");
            assertFalse(response.satisfied());
        }

        @Test
        @DisplayName("ссылки нет (старая привязка), но инстанс того же кода привязан → удовлетворён")
        void legacyFallbackByCode() {
            skill("telegram");
            bound(telegram);

            AgentSkillResponse response = service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of("telegram", TELEGRAM_ID));

            assertTrue(response.connectors().get(0).satisfied());
            assertEquals(TELEGRAM_ID, response.connectors().get(0).connectionId());
        }

        @Test
        @DisplayName("ничего не привязано → не удовлетворён, инстанса нет")
        void nothingBound() {
            skill("telegram");
            bound();

            AgentSkillResponse response = service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of("telegram", TELEGRAM_ID));

            assertFalse(response.satisfied());
            assertNull(response.connectors().get(0).connectionName());
        }
    }

    @Test
    @DisplayName("замена ссылок сносит прежние и не трогает привязки доступа")
    void replaceConnections() {
        skill("telegram");
        when(agentSkillRepository.findByAgentIdAndSkillId(AGENT_ID, SKILL_ID)).thenReturn(Optional.of(
                AgentSkill.builder().id(AGENT_SKILL_ID).agentId(AGENT_ID).userId(USER_ID).skillId(SKILL_ID).build()));

        service.replaceConnections(AGENT_ID, SKILL_ID, USER_ID, Map.of("telegram", TELEGRAM_ID));

        verify(agentSkillConnectionRepository).deleteByAgentSkillId(AGENT_SKILL_ID);
        assertEquals(TELEGRAM_ID, savedLinks().get(0).getConnectionId());
        verify(agentSkillPolicyService, never()).applyDiff(any(), any());
    }
}
