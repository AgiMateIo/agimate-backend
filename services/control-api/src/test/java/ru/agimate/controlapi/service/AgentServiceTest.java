package ru.agimate.controlapi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ValidationErrorStatusException;
import ru.agimate.controlapi.controller.manage.dto.PatchAgentRequest;
import ru.agimate.controlapi.controller.manage.dto.UpdateAgentRequest;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgenticTeam;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.AgenticTeamRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.SecretRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.service.dto.agent.AgentCreateCommand;
import ru.agimate.controlapi.service.http.PublicOnlyHttp;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentService")
class AgentServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SECRET_ID = UUID.randomUUID();

    @Mock
    private AgentRepository agentRepository;
    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private AgentDeliveryService agentDeliveryService;
    @Mock
    private AgentSkillRepository agentSkillRepository;
    @Mock
    private AgentLlmService agentLlmService;
    @Mock
    private SecretRepository secretRepository;
    @Mock
    private SkillRepository skillRepository;
    @Mock
    private AgentSkillService agentSkillService;
    @Mock
    private AgenticTeamRepository agenticTeamRepository;
    /** Настоящий, не мок: гард адреса — часть проверяемого здесь поведения, и он ничего не стоит. */
    @Spy
    private PublicOnlyHttp publicOnlyHttp = new PublicOnlyHttp(false);

    @InjectMocks
    private AgentService service;

    private final Agent agent = Agent.builder()
            .id(AGENT_ID).userId(USER_ID).name("agent").type(AgentType.CENTRIFUGO)
            // The response masks the key id, so it has to look like one
            .keyId("Z3h5YWJjZGVl").keyHash("hash").build();

    @BeforeEach
    void setUp() {
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));
        when(agentRepository.save(any(Agent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentSkillRepository.findSkillSummariesByAgentIdIn(any())).thenReturn(List.of());
        when(agentLlmService.listForAgents(anyList())).thenReturn(Map.of());
        when(agentDeliveryService.supportsPush(AgentType.MCP)).thenReturn(false);
        when(agentDeliveryService.supportsPush(AgentType.CENTRIFUGO)).thenReturn(true);
        when(agentDeliveryService.supportsPush(AgentType.GENERIC)).thenReturn(true);
        when(agentDeliveryService.supportsPush(AgentType.WEBHOOK)).thenReturn(true);
        when(channelRepository.findByAgentIdAndDeletedAtIsNullOrderByCreatedAtDesc(AGENT_ID))
                .thenReturn(List.of());
    }

    @Nested
    @DisplayName("create — агент в команде")
    class CreateInTeam {

        private final UUID teamId = UUID.randomUUID();
        private final UUID agentsSkillId = UUID.randomUUID();
        private final UUID otherSkillId = UUID.randomUUID();

        @BeforeEach
        void team() {
            when(agenticTeamRepository.findById(teamId)).thenReturn(Optional.of(
                    AgenticTeam.builder().id(teamId).userId(USER_ID).build()));
            when(agentRepository.save(any(Agent.class))).thenAnswer(invocation -> {
                Agent saved = invocation.getArgument(0);
                saved.setId(AGENT_ID);
                return saved;
            });
            when(skillRepository.findByUserIdAndNameNotDeleted(SystemSkillBootstrap.SYSTEM_USER_ID, "agents"))
                    .thenReturn(Optional.of(Skill.builder().id(agentsSkillId).name("agents").build()));
        }

        private AgentCreateCommand command(UUID team, List<UUID> skillIds) {
            return new AgentCreateCommand("agent", null, null, AgentType.GENERIC, null, null, team, skillIds, null);
        }

        @Test
        @DisplayName("созданный в команде получает навык agents вместе с навыками мастера")
        void teamAgentGetsAgentsSkill() {
            service.create(USER_ID, command(teamId, List.of(otherSkillId)));

            verify(agentSkillService).create(AGENT_ID, otherSkillId, USER_ID);
            verify(agentSkillService).create(AGENT_ID, agentsSkillId, USER_ID);
        }

        @Test
        @DisplayName("мастер уже выбрал agents — второй раз не привязывается")
        void noDuplicateWhenChosen() {
            service.create(USER_ID, command(teamId, List.of(agentsSkillId)));

            verify(agentSkillService, times(1)).create(any(), any(), any());
            verify(agentSkillService).create(AGENT_ID, agentsSkillId, USER_ID);
        }

        @Test
        @DisplayName("без команды навык не привязывается; без сида — создание не падает")
        void withoutTeamOrSeed() {
            service.create(USER_ID, command(null, null));
            verify(agentSkillService, never()).create(any(), any(), any());

            when(skillRepository.findByUserIdAndNameNotDeleted(SystemSkillBootstrap.SYSTEM_USER_ID, "agents"))
                    .thenReturn(Optional.empty());
            service.create(USER_ID, command(teamId, null));
            verify(agentSkillService, never()).create(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("update — смена типа агента")
    class Update {

        private UpdateAgentRequest toType(AgentType type) {
            return new UpdateAgentRequest("agent", null, null, type, null, null, null);
        }

        @Test
        @DisplayName("на тип без доставки при живом канале → 400, тип не меняется")
        void refusesWhenChannelsWouldBeOrphaned() {
            when(channelRepository.findByAgentIdAndDeletedAtIsNullOrderByCreatedAtDesc(AGENT_ID))
                    .thenReturn(List.of(mock(Channel.class)));

            assertThrows(BadRequestStatusException.class,
                    () -> service.update(AGENT_ID, USER_ID, toType(AgentType.MCP)));

            verify(agentRepository, never()).save(any());
            assertEquals(AgentType.CENTRIFUGO, agent.getType());
        }

        @Test
        @DisplayName("каналов нет → на тип без доставки переключиться можно")
        void allowsWhenThereAreNoChannels() {
            service.update(AGENT_ID, USER_ID, toType(AgentType.MCP));

            assertEquals(AgentType.MCP, agent.getType());
        }

        @Test
        @DisplayName("каналы есть, но тип с доставкой → проверка не мешает")
        void allowsPushableTypeWithChannels() {
            when(channelRepository.findByAgentIdAndDeletedAtIsNullOrderByCreatedAtDesc(AGENT_ID))
                    .thenReturn(List.of(mock(Channel.class)));

            service.update(AGENT_ID, USER_ID, toType(AgentType.CENTRIFUGO));

            assertEquals(AgentType.CENTRIFUGO, agent.getType());
        }
    }

    @Nested
    @DisplayName("patch — три состояния поля")
    class Patch {

        @BeforeEach
        void fillAgent() {
            agent.setDescription("описание");
            agent.setInstructions("инструкции");
            agent.setEnabled(true);
        }

        private PatchAgentRequest only(String name, String description, String instructions) {
            return new PatchAgentRequest(name, description, instructions, null, null, null, null);
        }

        @Test
        @DisplayName("поля нет в теле → значение сохраняется")
        void absentFieldIsUntouched() {
            service.patch(AGENT_ID, USER_ID, only("новое имя", null, null));

            assertEquals("новое имя", agent.getName());
            assertEquals("описание", agent.getDescription());
            assertEquals("инструкции", agent.getInstructions());
            assertTrue(agent.isEnabled());
        }

        @Test
        @DisplayName("пустая строка → поле очищается")
        void blankFieldIsCleared() {
            service.patch(AGENT_ID, USER_ID, only(null, "", ""));

            assertNull(agent.getDescription());
            assertNull(agent.getInstructions());
            assertEquals("agent", agent.getName());
        }

        @Test
        @DisplayName("enabled=false доезжает, остальное не трогается")
        void enabledIsWritten() {
            service.patch(AGENT_ID, USER_ID,
                    new PatchAgentRequest(null, null, null, null, null, null, false));

            assertFalse(agent.isEnabled());
            assertEquals("описание", agent.getDescription());
        }

        @Test
        @DisplayName("пустое имя → 400, агент без имени не бывает")
        void blankNameIsRejected() {
            assertThrows(ValidationErrorStatusException.class,
                    () -> service.patch(AGENT_ID, USER_ID, only("  ", null, null)));

            assertEquals("agent", agent.getName());
        }

        @Test
        @DisplayName("уход с WEBHOOK → сервер сам зануляет адрес и удаляет секрет заголовка")
        void leavingWebhookClearsTheWebhookPair() {
            agent.setType(AgentType.WEBHOOK);
            agent.setWebhookUrl("https://example.test/hook");
            agent.setWebhookAuthSecretId(SECRET_ID);

            service.patch(AGENT_ID, USER_ID,
                    new PatchAgentRequest(null, null, null, AgentType.GENERIC, null, null, null));

            assertEquals(AgentType.GENERIC, agent.getType());
            assertNull(agent.getWebhookUrl());
            assertNull(agent.getWebhookAuthSecretId());
            verify(secretRepository).deleteById(SECRET_ID);
        }

        @Test
        @DisplayName("переход в WEBHOOK: адрес берётся из базы, если в теле его нет")
        void storedWebhookUrlSatisfiesTheTransition() {
            agent.setWebhookUrl("https://example.test/hook");

            service.patch(AGENT_ID, USER_ID,
                    new PatchAgentRequest(null, null, null, AgentType.WEBHOOK, null, null, null));

            assertEquals(AgentType.WEBHOOK, agent.getType());
            assertEquals("https://example.test/hook", agent.getWebhookUrl());
        }

        @Test
        @DisplayName("адрес внутрь сети отвергается на записи, без резолва имени")
        void privateWebhookUrlIsRejected() {
            assertThrows(ValidationErrorStatusException.class,
                    () -> service.patch(AGENT_ID, USER_ID, new PatchAgentRequest(
                            null, null, null, AgentType.WEBHOOK,
                            "http://169.254.169.254/latest/meta-data/", null, null)));
        }

        @Test
        @DisplayName("переход в WEBHOOK без адреса где бы то ни было → 400")
        void webhookWithoutUrlIsRejected() {
            assertThrows(ValidationErrorStatusException.class,
                    () -> service.patch(AGENT_ID, USER_ID,
                            new PatchAgentRequest(null, null, null, AgentType.WEBHOOK, null, null, null)));

            assertEquals(AgentType.CENTRIFUGO, agent.getType());
        }

        @Test
        @DisplayName("проверка каналов работает и в patch")
        void channelGuardApplies() {
            when(channelRepository.findByAgentIdAndDeletedAtIsNullOrderByCreatedAtDesc(AGENT_ID))
                    .thenReturn(List.of(mock(Channel.class)));

            assertThrows(BadRequestStatusException.class,
                    () -> service.patch(AGENT_ID, USER_ID,
                            new PatchAgentRequest(null, null, null, AgentType.MCP, null, null, null)));

            assertEquals(AgentType.CENTRIFUGO, agent.getType());
        }
    }
}
