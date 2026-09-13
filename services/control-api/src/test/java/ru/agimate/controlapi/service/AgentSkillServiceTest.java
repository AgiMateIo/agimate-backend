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
import ru.agimate.controlapi.controller.manage.dto.SkillBindingPlanResponse;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.AgentSkillConnection;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.enums.ConnectionAuthStatus;
import ru.agimate.controlapi.database.enums.DefinitionBinding;
import ru.agimate.controlapi.database.enums.Disclosure;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillConnectionRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.ConnectionTriggerRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;
import ru.agimate.controlapi.service.connection.ConnectionBindingService.ConnectorKind;

import ru.agimate.controlapi.database.model.ConnectorRequirement;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.abac.SkillPolicySync;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
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
    private AgentSkillConnectionRepository agentSkillConnectionRepository;
    @Mock
    private ConnectionBindingService connectionBindingService;
    @Mock
    private ConnectorRepository connectorRepository;
    @Mock
    private ConnectionToolRepository connectionToolRepository;
    @Mock
    private ConnectionTriggerRepository connectionTriggerRepository;
    @Mock
    private ConnectorRegistry connectorRegistry;
    @Mock
    private SkillPolicySync policySync;

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
        when(connectionRepository.findByUserIdNotDeleted(USER_ID)).thenReturn(List.of());
        when(connectorRepository.findAll()).thenReturn(List.of());
        when(connectorRegistry.findIntegrationHandler(any())).thenReturn(Optional.empty());
        when(policySync.apply(any(), any(), any(), any())).thenReturn(List.of());
        when(policySync.conflicts(any(), any(), any(), any())).thenReturn(List.of());
    }

    private void skill(String... connectorCodes) {
        skill(ConnectorRequirement.ofCodes(List.of(connectorCodes)));
    }

    private void skill(List<ConnectorRequirement> connectors) {
        Skill skill = Skill.builder()
                .id(SKILL_ID).userId(USER_ID).name("skill").version(1)
                .connectors(connectors)
                .build();
        when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(skill));
        // The status is read back through the same resolution the run context uses, and that one starts
        // from the agent's skills — so the binding has to be visible to it.
        when(agentSkillRepository.findByAgentId(AGENT_ID)).thenReturn(List.of(
                AgentSkill.builder().id(AGENT_SKILL_ID).agentId(AGENT_ID)
                        .userId(USER_ID).skillId(SKILL_ID).build()));
        when(skillRepository.findByIdInNotDeleted(any())).thenReturn(List.of(skill));
    }

    private static ConnectorRequirement requirement(String code, String key) {
        return new ConnectorRequirement(code, key, null, null, null, null);
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
        @DisplayName("внешний коннектор без ссылки → привязан неудовлетворённым, строки нет (агент из пресета)")
        void externalWithoutChoiceIsBoundUnsatisfied() {
            skill("telegram");

            AgentSkillResponse response = service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of(), null);

            assertTrue(savedLinks().isEmpty());
            assertFalse(response.satisfied());
            assertNull(response.connectors().get(0).connectionId());
        }

        @Test
        @DisplayName("ссылка на инстанс чужого коннектора → 400")
        void externalMustMatchTheCode() {
            skill("telegram");
            UUID gmailId = UUID.randomUUID();
            when(connectionRepository.findByIdAndUserIdNotDeleted(gmailId, USER_ID))
                    .thenReturn(Optional.of(connection(gmailId, "gmail", "Почта")));

            assertThrows(BadRequestStatusException.class,
                    () -> service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of("telegram", gmailId), null));
        }

        @Test
        @DisplayName("код, который навык не объявлял → 400")
        void rejectsUndeclaredCode() {
            skill("telegram");

            assertThrows(BadRequestStatusException.class, () -> service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of("telegram", TELEGRAM_ID, "gmail", UUID.randomUUID()), null));
        }

        @Test
        @DisplayName("внутренний коннектор — ссылку ставит сервер, id от клиента не нужен")
        void internalIsResolvedByTheServer() {
            skill("persist-memory");

            service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of(), null);

            List<AgentSkillConnection> links = savedLinks();
            assertEquals(1, links.size());
            assertEquals("persist-memory", links.get(0).getConnectorKey());
            assertEquals(MEMORY_MODE_ID, links.get(0).getConnectionId());
        }

        @Test
        @DisplayName("внутренний коннектор с чужим id → 400, а не тихая подмена")
        void internalRejectsAWrongId() {
            skill("persist-memory");

            assertThrows(BadRequestStatusException.class, () -> service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of("persist-memory", UUID.randomUUID()), null));
        }

        @Test
        @DisplayName("два требования одного кода под разными ключами → две ссылки, каждая по своему ключу")
        void twoKeysOfOneCode() {
            UUID secondId = UUID.randomUUID();
            when(connectionRepository.findByIdAndUserIdNotDeleted(secondId, USER_ID))
                    .thenReturn(Optional.of(connection(secondId, "telegram", "Личный")));
            skill(List.of(requirement("telegram", "work"), requirement("telegram", "personal")));

            service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of("work", TELEGRAM_ID, "personal", secondId), null);

            List<AgentSkillConnection> links = savedLinks();
            assertEquals(2, links.size());
            assertEquals("work", links.get(0).getConnectorKey());
            assertEquals(TELEGRAM_ID, links.get(0).getConnectionId());
            assertEquals("personal", links.get(1).getConnectorKey());
            assertEquals(secondId, links.get(1).getConnectionId());
        }

        @Test
        @DisplayName("ключ нужен и в запросе: код вместо ключа — это необъявленное имя → 400")
        void requestIsKeyedByKey() {
            skill(List.of(requirement("telegram", "work")));

            assertThrows(BadRequestStatusException.class,
                    () -> service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of("telegram", TELEGRAM_ID), null));
        }

        @Test
        @DisplayName("неизвестный коннектор навык не ломает — строки нет, статус неудовлетворён")
        void unknownConnectorIsSkipped() {
            skill("ghost");

            AgentSkillResponse response = service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of(), null);

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
                            .agentSkillId(AGENT_SKILL_ID).connectorKey(code).connectionId(connectionId).build()));
        }

        @Test
        @DisplayName("ссылка есть и инстанс привязан → удовлетворён, видно имя инстанса")
        void referencedAndBound() {
            skill("telegram");
            bound(telegram);
            referenced("telegram", TELEGRAM_ID);

            AgentSkillResponse response = service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of("telegram", TELEGRAM_ID), null);

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

            AgentSkillResponse response = service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of("telegram", TELEGRAM_ID), null);

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

            AgentSkillResponse response = service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of("telegram", TELEGRAM_ID), null);

            assertTrue(response.connectors().get(0).satisfied());
            assertEquals(TELEGRAM_ID, response.connectors().get(0).connectionId());
        }

        @Test
        @DisplayName("ничего не привязано → не удовлетворён, инстанса нет")
        void nothingBound() {
            skill("telegram");
            bound();

            AgentSkillResponse response = service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of("telegram", TELEGRAM_ID), null);

            assertFalse(response.satisfied());
            assertNull(response.connectors().get(0).connectionName());
        }
    }

    @Nested
    @DisplayName("гейт — что доедет до агента")
    class SatisfiedInstances {

        private final UUID otherTelegramId = UUID.randomUUID();

        private void referenced(String code, UUID connectionId) {
            when(agentSkillConnectionRepository.findByAgentSkillIdIn(anyList())).thenReturn(List.of(
                    AgentSkillConnection.builder()
                            .agentSkillId(AGENT_SKILL_ID).connectorKey(code).connectionId(connectionId).build()));
        }

        @Test
        @DisplayName("ссылка на непривязанный инстанс → навыка нет вовсе")
        void unsatisfiedSkillIsNotDelivered() {
            skill("telegram");
            referenced("telegram", TELEGRAM_ID);
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of());

            assertTrue(service.gate(AGENT_ID).satisfied().isEmpty());
        }

        @Test
        @DisplayName("два телеграма привязаны, ссылка на один → в гейт уходит только он")
        void gateNarrowsToTheChosenInstance() {
            skill("telegram");
            referenced("telegram", TELEGRAM_ID);
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID))
                    .thenReturn(List.of(telegram, connection(otherTelegramId, "telegram", "Личный")));

            assertEquals(Map.of(SKILL_ID, java.util.Set.of(TELEGRAM_ID)),
                    service.gate(AGENT_ID).satisfied());
        }

        @Test
        @DisplayName("старая привязка без ссылки → в гейт уходят все инстансы кода, как было")
        void legacyKeepsEveryInstanceOfTheCode() {
            skill("telegram");
            when(agentSkillConnectionRepository.findByAgentSkillIdIn(anyList())).thenReturn(List.of());
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID))
                    .thenReturn(List.of(telegram, connection(otherTelegramId, "telegram", "Личный")));

            assertEquals(Map.of(SKILL_ID, java.util.Set.of(TELEGRAM_ID, otherTelegramId)),
                    service.gate(AGENT_ID).satisfied());
        }

        @Test
        @DisplayName("счётчик у коннекшена и статус навыка отвечают одинаково — и на старой привязке")
        void counterAgreesWithTheStatus() {
            skill("telegram");
            when(agentSkillConnectionRepository.findByAgentSkillIdIn(anyList())).thenReturn(List.of());
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of(telegram));

            // Навык считает себя удовлетворённым по фолбэку «любой инстанс кода» — значит и коннекшен
            // обязан видеть, что им пользуются: иначе он выглядит мёртвым и его предложат отвязать.
            assertFalse(service.gate(AGENT_ID).satisfied().isEmpty());
            assertEquals(Map.of(TELEGRAM_ID, 1L), service.skillReferencesByConnection(AGENT_ID));
        }

        @Test
        @DisplayName("хватает не всех коннекторов → навык не отдаётся целиком")
        void allOrNothing() {
            skill("telegram", "persist-memory");
            referenced("telegram", TELEGRAM_ID);
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of(telegram));

            assertTrue(service.gate(AGENT_ID).satisfied().isEmpty(),
                    "память не привязана — половина навыка не отдаётся");
        }
    }

    @Nested
    @DisplayName("причина, по которой навык не доехал")
    class Blockers {

        private AgentSkillService.WithheldSkill onlyWithheld() {
            List<AgentSkillService.WithheldSkill> withheld = service.gate(AGENT_ID).withheld();
            assertEquals(1, withheld.size(), "навык должен быть удержан");
            return withheld.get(0);
        }

        private AgentSkillService.RequirementState stateOf(String key) {
            return onlyWithheld().blockers().stream()
                    .filter(blocker -> blocker.key().equals(key))
                    .findFirst().orElseThrow().state();
        }

        @Test
        @DisplayName("нечему отвечать за требование → NOT_CHOSEN, и навык назван")
        void notChosen() {
            skill("telegram");

            AgentSkillService.WithheldSkill withheld = onlyWithheld();
            assertEquals(SKILL_ID, withheld.skillId());
            assertEquals("skill", withheld.name());
            assertEquals(AgentSkillService.RequirementState.NOT_CHOSEN, withheld.blockers().get(0).state());
            assertEquals("telegram", withheld.blockers().get(0).code());
        }

        @Test
        @DisplayName("выбранный экземпляр не привязан к агенту → NOT_BOUND")
        void notBound() {
            skill("telegram");
            when(agentSkillConnectionRepository.findByAgentSkillIdIn(anyList())).thenReturn(List.of(
                    AgentSkillConnection.builder().agentSkillId(AGENT_SKILL_ID)
                            .connectorKey("telegram").connectionId(TELEGRAM_ID).build()));
            when(connectionRepository.findByIdInNotDeleted(anyList())).thenReturn(List.of(telegram));

            assertEquals(AgentSkillService.RequirementState.NOT_BOUND, stateOf("telegram"));
        }

        @Test
        @DisplayName("привязан, но авторизация умерла → UNAUTHORIZED, навык не доезжает")
        void unauthorized() {
            skill("telegram");
            Connection expired = connection(TELEGRAM_ID, "telegram", "Рабочий");
            expired.setAuthStatus(ConnectionAuthStatus.AUTH_EXPIRED);
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of(expired));

            // Раньше такой экземпляр проходил гейт: навык доезжал, а каждый вызов падал 401
            assertTrue(service.gate(AGENT_ID).satisfied().isEmpty());
            assertEquals(AgentSkillService.RequirementState.UNAUTHORIZED, stateOf("telegram"));
        }

        @Test
        @DisplayName("DYNAMIC-экземпляр без живых тулов → NO_CAPABILITIES")
        void noTools() {
            UUID mcpId = UUID.randomUUID();
            skill(List.of(requirement("mcp", "mcp")));
            Connection mcp = connection(mcpId, "mcp", "Context7");
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of(mcp));
            Connector connector = new Connector();
            connector.setCode("mcp");
            connector.setDefinitionBinding(DefinitionBinding.DYNAMIC);
            when(connectorRepository.findAllById(any())).thenReturn(List.of(connector));
            when(connectionToolRepository.findIdsWithActiveTools(anyList())).thenReturn(Set.of());
            when(connectionTriggerRepository.findIdsWithActiveTriggers(anyList())).thenReturn(Set.of());

            assertEquals(AgentSkillService.RequirementState.NO_CAPABILITIES, stateOf("mcp"));
        }

        @Test
        @DisplayName("тулов нет, но триггеры есть → не поломка: app объявляется ради событий")
        void triggersAloneAreEnough() {
            UUID appId = UUID.randomUUID();
            skill(List.of(requirement("app", "app")));
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID))
                    .thenReturn(List.of(connection(appId, "app", "Телефон")));
            Connector connector = new Connector();
            connector.setCode("app");
            connector.setDefinitionBinding(DefinitionBinding.DYNAMIC);
            when(connectorRepository.findAllById(any())).thenReturn(List.of(connector));
            when(connectionToolRepository.findIdsWithActiveTools(anyList())).thenReturn(Set.of());
            when(connectionTriggerRepository.findIdsWithActiveTriggers(anyList())).thenReturn(Set.of(appId));

            assertTrue(service.gate(AGENT_ID).withheld().isEmpty());
        }

        @Test
        @DisplayName("сломан один инстанс из двух под безымянным требованием → навык живёт на рабочем")
        void oneBrokenInstanceDoesNotPoisonTheCodeWideFallback() {
            skill("telegram");
            UUID otherId = UUID.randomUUID();
            Connection expired = connection(otherId, "telegram", "Личный");
            expired.setAuthStatus(ConnectionAuthStatus.AUTH_EXPIRED);
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of(telegram, expired));

            // Требование не называет экземпляр — фолбэк значит «любой этого кода», а не «все сразу»
            assertEquals(Map.of(SKILL_ID, Set.of(TELEGRAM_ID)), service.gate(AGENT_ID).satisfied());
        }

        @Test
        @DisplayName("тот же ответ видит листинг: satisfied=false и та же причина")
        void listingReadsTheSameState() {
            skill("telegram");
            Connection expired = connection(TELEGRAM_ID, "telegram", "Рабочий");
            expired.setAuthStatus(ConnectionAuthStatus.AUTH_EXPIRED);
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of(expired));
            when(agentSkillRepository.findByAgentId(eq(AGENT_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(AgentSkill.builder().id(AGENT_SKILL_ID)
                            .agentId(AGENT_ID).userId(USER_ID).skillId(SKILL_ID).installedSkillVersion(1).build())));

            SkillConnectorStatus status = service.getAgentSkills(AGENT_ID, USER_ID, 0, 20)
                    .getContent().get(0).connectors().get(0);

            assertFalse(status.satisfied());
            assertEquals(AgentSkillService.RequirementState.UNAUTHORIZED, status.state());
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
    }

    @Nested
    @DisplayName("ось раскрытия на привязке")
    class DisclosureOverride {

        private Skill lazySkill() {
            Skill skill = Skill.builder()
                    .id(SKILL_ID).userId(USER_ID).name("skill").version(1)
                    .connectors(ConnectorRequirement.ofCodes(List.of("persist-memory"))).disclosure(Disclosure.LAZY)
                    .build();
            when(skillRepository.findByIdNotDeleted(SKILL_ID)).thenReturn(Optional.of(skill));
            when(skillRepository.findByIdInNotDeleted(any())).thenReturn(List.of(skill));
            return skill;
        }

        private AgentSkill binding(Disclosure override) {
            AgentSkill binding = AgentSkill.builder().id(AGENT_SKILL_ID).agentId(AGENT_ID)
                    .userId(USER_ID).skillId(SKILL_ID).disclosure(override).build();
            when(agentSkillRepository.findByAgentId(AGENT_ID)).thenReturn(List.of(binding));
            when(agentSkillRepository.findByAgentIdAndSkillId(AGENT_ID, SKILL_ID)).thenReturn(Optional.of(binding));
            return binding;
        }

        @Test
        @DisplayName("без переопределения действует ось навыка")
        void inheritsTheSkillAxis() {
            lazySkill();
            binding(null);

            AgentSkillResponse response = service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of(), null);

            assertEquals(Disclosure.LAZY, response.disclosure());
            assertNull(response.disclosureOverride());
        }

        @Test
        @DisplayName("переопределение на привязке перекрывает ось навыка — и в ответе, и в том, что читает ран")
        void overrideWins() {
            lazySkill();
            AgentSkill binding = binding(Disclosure.EAGER);

            AgentSkillResponse response = service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of(), Disclosure.EAGER);

            assertEquals(Disclosure.EAGER, response.disclosure());
            assertEquals(Disclosure.EAGER, response.disclosureOverride());
            assertEquals(Disclosure.EAGER, service.resolveSkills(List.of(binding)).get(SKILL_ID).disclosure());
        }

        @Test
        @DisplayName("PATCH с INHERIT снимает переопределение — навык снова по своей оси")
        void inheritDropsTheOverride() {
            lazySkill();
            AgentSkill binding = binding(Disclosure.EAGER);

            AgentSkillResponse response = service.updateDisclosure(AGENT_ID, SKILL_ID, USER_ID, null);

            assertNull(binding.getDisclosure());
            assertEquals(Disclosure.LAZY, response.disclosure());
            assertNull(response.disclosureOverride());
        }
    }

    @Nested
    @DisplayName("требование с идентичностью — адрес сервера решает, какой экземпляр")
    class Identity {

        private final UUID context7Id = UUID.randomUUID();
        private final UUID githubId = UUID.randomUUID();
        private final Connection context7 = mcp(context7Id, "https://mcp.context7.com/mcp", "Context7");
        private final Connection github = mcp(githubId, "https://api.githubcopilot.com/mcp/", "GitHub");

        private static Connection mcp(UUID id, String url, String name) {
            return Connection.builder().id(id).userId(USER_ID).connectorCode("mcp").subCode(url)
                    .fullCode("mcp_" + name).name(name).enabled(true).build();
        }

        private static ConnectorRequirement docs() {
            return new ConnectorRequirement("mcp", "docs", null, Map.of("url", "https://mcp.context7.com/mcp"), null, null);
        }

        @BeforeEach
        void mcpHandler() {
            IntegrationConnectorHandler handler = mock(IntegrationConnectorHandler.class);
            when(handler.identifierOf(any())).thenAnswer(inv -> Optional.ofNullable(inv.<Map<String, String>>getArgument(0).get("url")));
            when(handler.getCredentialFields()).thenReturn(Map.of());
            when(connectorRegistry.findIntegrationHandler("mcp")).thenReturn(Optional.of(handler));
            when(connectionBindingService.kindOf("mcp")).thenReturn(ConnectorKind.EXTERNAL);
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of(context7, github));
            when(connectionRepository.findByUserIdNotDeleted(USER_ID)).thenReturn(List.of(context7, github));
        }

        @Test
        @DisplayName("без ссылки в гейт уходит только сервер с объявленным адресом, а не оба MCP")
        void fallbackNarrowsByIdentity() {
            skill(List.of(docs()));

            assertEquals(Map.of(SKILL_ID, java.util.Set.of(context7Id)), service.gate(AGENT_ID).satisfied());
        }

        @Test
        @DisplayName("объявлен адрес, которого у агента нет → навык не удовлетворён, хоть другой MCP и привязан")
        void unknownIdentityIsNotSatisfiedByAnotherServer() {
            skill(List.of(new ConnectorRequirement("mcp", "docs", null, Map.of("url", "https://other/mcp"), null, null)));

            assertTrue(service.gate(AGENT_ID).satisfied().isEmpty());
        }

        @Test
        @DisplayName("план: подходит только коннекция с тем же sub_code, у неё видно, что она уже привязана")
        void planMatchesByIdentity() {
            skill(List.of(docs()));

            SkillBindingPlanResponse plan = service.plan(AGENT_ID, SKILL_ID, USER_ID);

            SkillConnectorStatus status = plan.connectors().get(0);
            assertEquals("docs", status.key());
            assertEquals("docs", status.title(), "ключ говорит больше кода — он и подпись");
            assertEquals("https://mcp.context7.com/mcp", status.identity());
            assertEquals(1, status.matches().size());
            assertEquals(context7Id, status.matches().get(0).connectionId());
            assertTrue(status.matches().get(0).boundToAgent());
            assertTrue(status.satisfied());
            assertTrue(plan.satisfied());
        }

        @Test
        @DisplayName("план без идентичности предлагает все экземпляры кода, непривязанные — как есть")
        void planWithoutIdentityOffersEveryInstance() {
            skill("mcp");
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of());

            SkillBindingPlanResponse plan = service.plan(AGENT_ID, SKILL_ID, USER_ID);

            SkillConnectorStatus status = plan.connectors().get(0);
            assertNull(status.identity());
            assertEquals(2, status.matches().size());
            assertFalse(status.matches().get(0).boundToAgent());
            assertFalse(status.satisfied());
            assertNull(status.connectionId());
        }
    }

    @Nested
    @DisplayName("правила навыка")
    class Policies {

        private final ConnectorRequirement withRules = new ConnectorRequirement("telegram", "telegram", null, null,
                new ConnectorRequirement.Rules(null, List.of("send_photo")), null);

        @Test
        @DisplayName("привязка с выбранным экземпляром пишет правила на его binding")
        void bindingAppliesRules() {
            skill(List.of(withRules));

            service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of("telegram", TELEGRAM_ID), null);

            verify(policySync).apply(AGENT_ID, TELEGRAM_ID, SKILL_ID, withRules);
        }

        @Test
        @DisplayName("без правил в объявлении sync не зовётся")
        void noRulesNoSync() {
            skill("telegram");

            service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of("telegram", TELEGRAM_ID), null);

            verify(policySync, never()).apply(any(), any(), any(), any());
        }

        @Test
        @DisplayName("конфликты попадают в статус требования")
        void conflictsSurfaceInStatus() {
            skill(List.of(withRules));
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of(telegram));
            when(agentSkillConnectionRepository.findByAgentSkillIdIn(anyList())).thenReturn(List.of(
                    AgentSkillConnection.builder().agentSkillId(AGENT_SKILL_ID).connectorKey("telegram")
                            .connectionId(TELEGRAM_ID).build()));
            when(policySync.conflicts(AGENT_ID, TELEGRAM_ID, SKILL_ID, withRules)).thenReturn(List.of("TOOL/send_photo"));

            AgentSkillResponse response = service.create(AGENT_ID, SKILL_ID, USER_ID, Map.of("telegram", TELEGRAM_ID), null);

            assertEquals(List.of("TOOL/send_photo"), response.connectors().get(0).policyConflicts());
            assertEquals(1, response.connectors().get(0).policies().size());
        }

        @Test
        @DisplayName("отвязка навыка уносит его правила с этого агента")
        void unbindRemovesRules() {
            skill("telegram");
            when(agentSkillRepository.findByAgentIdAndSkillId(AGENT_ID, SKILL_ID)).thenReturn(Optional.of(
                    AgentSkill.builder().id(AGENT_SKILL_ID).agentId(AGENT_ID).userId(USER_ID).skillId(SKILL_ID).build()));

            service.delete(AGENT_ID, SKILL_ID, USER_ID);

            verify(policySync).remove(AGENT_ID, SKILL_ID);
        }

        @Test
        @DisplayName("refresh переприменяет правила по сохранённым ссылкам")
        void refreshReapplies() {
            skill(List.of(withRules));
            when(agentSkillConnectionRepository.findByAgentSkillId(AGENT_SKILL_ID)).thenReturn(List.of(
                    AgentSkillConnection.builder().agentSkillId(AGENT_SKILL_ID).connectorKey("telegram")
                            .connectionId(TELEGRAM_ID).build()));

            service.markSkillsInstalled(AGENT_ID, USER_ID);

            verify(policySync).apply(AGENT_ID, TELEGRAM_ID, SKILL_ID, withRules);
        }
    }
}
