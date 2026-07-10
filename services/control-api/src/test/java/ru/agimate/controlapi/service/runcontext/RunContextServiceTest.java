package ru.agimate.controlapi.service.runcontext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvFactory;
import ru.agimate.controlapi.connectors.core.ConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.PromptBlockProvider;
import ru.agimate.controlapi.connectors.core.ToolProvider;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.PromptBlock;
import ru.agimate.controlapi.controller.agent.dto.AgentSkillWithConnectorsResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.TriggerLog;
import ru.agimate.controlapi.database.entities.TriggerLogAgent;
import ru.agimate.controlapi.database.enums.IdentityScope;
import ru.agimate.controlapi.database.enums.ToolBinding;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.AgenticTeamRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.database.repositories.TriggerLogAgentRepository;
import ru.agimate.controlapi.service.AgentSkillService;
import ru.agimate.controlapi.service.channel.handler.ChannelHandler;
import ru.agimate.controlapi.service.channel.handler.ChannelHandlerRegistry;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.trigger.ChannelInfo;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.ChannelsCodec;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunContextService")
class RunContextServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TRIGGER_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final UUID CHANNEL_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock private TriggerLogAgentRepository triggerLogAgentRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AgenticTeamRepository agenticTeamRepository;
    @Mock private AgentSkillRepository agentSkillRepository;
    @Mock private AgentSkillService agentSkillService;
    @Mock private SkillRepository skillRepository;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private ConnectorRepository connectorRepository;
    @Mock private ConnectionToolRepository connectionToolRepository;
    @Mock private ChannelRepository channelRepository;
    @Mock private ChannelHandlerRegistry channelHandlerRegistry;

    /** persist-memory-подобный коннектор: identity + блоки + статические тулы. */
    interface MemoryLikeHandler extends ConnectorHandler, PromptBlockProvider, ToolProvider {
    }

    private MemoryLikeHandler memoryHandler;
    private RunContextService service;

    @BeforeEach
    void setUp() {
        memoryHandler = mock(MemoryLikeHandler.class);
        lenient().when(memoryHandler.connectorCode()).thenReturn("persist-memory");
        ConnectorRegistry registry = new ConnectorRegistry(List.of(memoryHandler));
        service = new RunContextService(triggerLogAgentRepository, agentRepository,
                agenticTeamRepository, agentSkillRepository, agentSkillService, skillRepository,
                connectionRepository, connectorRepository, connectionToolRepository,
                registry, new ConnectorEnvFactory(null, null), channelRepository, channelHandlerRegistry);
    }

    private Agent agent() {
        return Agent.builder().id(AGENT_ID).userId(USER_ID).name("Bot")
                .instructions("You are helpful.").enabled(true).build();
    }

    private TriggerLog triggerLog(String connectorCode, String name) {
        return TriggerLog.builder()
                .connectorCode(connectorCode)
                .connectionId(CONNECTION_ID.toString())
                .externalId("evt-1")
                .name(name)
                .input(Map.of("text", "hello agent"))
                .build();
    }

    private TriggerLogAgent run(Agent agent, TriggerLog log, Channels channels) {
        return TriggerLogAgent.builder()
                .agent(agent)
                .triggerLog(log)
                .destination("GENERIC")
                .channels(ChannelsCodec.toMap(channels))
                .build();
    }

    private void stubRun(TriggerLogAgent run) {
        when(triggerLogAgentRepository.findById(TRIGGER_ID)).thenReturn(Optional.of(run));
    }

    private void stubSkills(List<AgentSkillWithConnectorsResponse> skills) {
        List<AgentSkill> refs = skills.stream()
                .map(s -> {
                    AgentSkill ref = new AgentSkill();
                    ref.setSkillId(s.skillId());
                    return ref;
                })
                .toList();
        when(agentSkillRepository.findByAgentId(AGENT_ID)).thenReturn(refs);
        when(agentSkillService.resolveSkillsById(anyList())).thenReturn(
                skills.stream().collect(java.util.stream.Collectors.toMap(
                        AgentSkillWithConnectorsResponse::skillId, s -> s)));
    }

    private Connection memoryConnection() {
        return Connection.builder()
                .id(CONNECTION_ID)
                .userId(USER_ID)
                .connectorCode("persist-memory")
                .identityScope(IdentityScope.AGENT)
                .build();
    }

    @Nested
    @DisplayName("SYSTEM_TRIGGER (direct-ран)")
    class SystemTrigger {

        @Test
        @DisplayName("guidance + untrusted event-блок; тулы и тела только подошедших скиллов")
        void buildsTriggerContext() {
            Agent agent = agent();
            stubRun(run(agent, triggerLog("time", "due"), null));
            UUID timeSkill = UUID.randomUUID();
            UUID otherSkill = UUID.randomUUID();
            stubSkills(List.of(
                    new AgentSkillWithConnectorsResponse(timeSkill, "Reminders", "d1", List.of("time")),
                    new AgentSkillWithConnectorsResponse(otherSkill, "Boards", "d2", List.of("board"))));
            when(skillRepository.findByIdNotDeleted(timeSkill)).thenReturn(Optional.of(
                    ru.agimate.controlapi.database.entities.Skill.builder()
                            .id(timeSkill).name("Reminders").mdContent("Skill body here").version(1).build()));
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of());

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            List<String> names = view.systemBlocks().stream().map(RunBlock::name).toList();
            assertTrue(names.contains("agent"));
            assertTrue(names.contains("skills"));
            assertTrue(names.contains("skill"));
            assertTrue(names.contains("trigger_guidance"));

            RunBlock event = view.userBlocks().get(view.userBlocks().size() - 1);
            assertEquals("event", event.name());
            assertFalse(event.trusted());
            assertTrue(event.content().contains("hello agent"));
            assertEquals("time", event.attrs().get("connector"));

            // Тулы: связей нет — пусто; но скоуп считался от matched-скилла (time), не от board.
            assertTrue(view.tools().isEmpty());
        }
    }

    @Nested
    @DisplayName("DIALOGUE (prompt-канал)")
    class Dialogue {

        @Test
        @DisplayName("основной промпт — trusted текст из ChannelHandler; guidance нет; тулы всех скиллов")
        void buildsDialogueContext() {
            Agent agent = agent();
            Channels channels = Channels.ofPrompt(new ChannelInfo(CHANNEL_ID, SESSION_ID, null));
            stubRun(run(agent, triggerLog("webchat", "message_received"), channels));
            stubSkills(List.of(new AgentSkillWithConnectorsResponse(
                    UUID.randomUUID(), "Memory", "d", List.of("persist-memory"))));

            Channel channel = Channel.builder()
                    .id(CHANNEL_ID).agentId(AGENT_ID).connectorCode("webchat")
                    .connectionId(CONNECTION_ID).channelHandler("webchat").build();
            when(channelRepository.findById(CHANNEL_ID)).thenReturn(Optional.of(channel));
            ChannelHandler handler = mock(ChannelHandler.class);
            when(channelHandlerRegistry.find("webchat")).thenReturn(Optional.of(handler));
            when(handler.handleInput(any(), any()))
                    .thenReturn(Optional.of(InboundMessage.text("hello agent")));

            // memory-коннектор привязан: system-блок memory + ephemeral user-блок notes + тул.
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID))
                    .thenReturn(List.of(memoryConnection()));
            when(memoryHandler.promptBlocks(any(ConnectorEnv.class))).thenReturn(List.of(
                    PromptBlock.system("memory", "facts", Map.of("version", "3")),
                    PromptBlock.user("memory_notes", "- note")));
            Connector connector = new Connector();
            connector.setCode("persist-memory");
            connector.setToolBinding(ToolBinding.STATIC);
            when(connectorRepository.findById("persist-memory")).thenReturn(Optional.of(connector));
            when(memoryHandler.getTools(any(ConnectorEnv.class))).thenReturn(Map.of(
                    "get_memory", new ConnectorToolSpec("get_memory", null, "d", null, null, null, null)));

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            List<String> systemNames = view.systemBlocks().stream().map(RunBlock::name).toList();
            assertTrue(systemNames.contains("memory"));
            assertFalse(systemNames.contains("trigger_guidance"));

            RunBlock main = view.userBlocks().get(view.userBlocks().size() - 1);
            assertEquals("", main.name());
            assertTrue(main.trusted());
            assertEquals("hello agent", main.content());

            RunBlock notes = view.userBlocks().get(0);
            assertEquals("memory_notes", notes.name());
            assertTrue(notes.ephemeral());

            assertEquals(1, view.tools().size());
            RunTool tool = view.tools().get(0);
            assertEquals("persist-memory", tool.connectorCode());
            assertEquals("persist-memory", tool.namespace());
            assertEquals(CONNECTION_ID.toString(), tool.connectionId());
        }
    }

    @Nested
    @DisplayName("Валидация")
    class Validation {

        @Test
        @DisplayName("неизвестный trigger_id → NotFound")
        void unknownRun() {
            when(triggerLogAgentRepository.findById(TRIGGER_ID)).thenReturn(Optional.empty());
            assertThrows(NotFoundStatusException.class, () -> service.build(AGENT_ID, TRIGGER_ID));
        }

        @Test
        @DisplayName("ран чужого агента → BadRequest")
        void foreignRun() {
            stubRun(run(agent(), triggerLog("time", "due"), null));
            assertThrows(BadRequestStatusException.class,
                    () -> service.build(UUID.randomUUID(), TRIGGER_ID));
        }
    }
}
