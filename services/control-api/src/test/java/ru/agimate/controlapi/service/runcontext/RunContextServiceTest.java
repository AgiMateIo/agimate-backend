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
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.PromptBlockProvider;
import ru.agimate.controlapi.connectors.core.ToolProvider;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.ContextDirectives;
import ru.agimate.controlapi.connectors.core.dto.PromptBlock;
import ru.agimate.controlapi.controller.agent.dto.AgentSkillWithConnectorsResponse;
import ru.agimate.controlapi.database.enums.Disclosure;
import ru.agimate.controlapi.config.ContentProperties;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.TriggerLog;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.enums.ConnectionAuthStatus;
import ru.agimate.controlapi.database.enums.DefinitionBinding;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.AgenticTeamRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.service.AgentSkillService;
import ru.agimate.controlapi.service.channel.InboundTextResolver;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.seed.PromptTexts;
import ru.agimate.controlapi.service.trigger.ChannelInfo;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.ChannelsCodec;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock private AgentRunRepository agentRunRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AgenticTeamRepository agenticTeamRepository;
    @Mock private AgentSkillRepository agentSkillRepository;
    @Mock private AgentSkillService agentSkillService;
    @Mock private SkillRepository skillRepository;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private ConnectorRepository connectorRepository;
    @Mock private ConnectionToolRepository connectionToolRepository;
    @Mock private InboundTextResolver inboundTextResolver;
    @Mock private RunHistoryAssembler historyAssembler;
    @Mock private ru.agimate.controlapi.database.repositories.ChannelRepository channelRepository;
    @Mock private ru.agimate.controlapi.service.channel.handler.ChannelHandlerRegistry channelHandlerRegistry;

    /** persist-memory-подобный коннектор: internal identity + блоки + статические тулы. */
    interface MemoryLikeHandler extends InternalConnectorHandler, PromptBlockProvider, ToolProvider {
    }

    /** time-подобный коннектор: internal identity + триггеры с директивами + статические тулы. */
    interface TimeLikeHandler extends InternalConnectorHandler, TriggerProvider, ToolProvider {
    }

    private MemoryLikeHandler memoryHandler;
    private TimeLikeHandler timeHandler;
    private RunCatalog catalog;
    private RunContextService service;

    @BeforeEach
    void setUp() {
        memoryHandler = mock(MemoryLikeHandler.class);
        lenient().when(memoryHandler.connectorCode()).thenReturn("persist-memory");
        timeHandler = mock(TimeLikeHandler.class);
        lenient().when(timeHandler.connectorCode()).thenReturn("time");
        // A mock answers null for a record; every build() reads the history, so the empty one is the default.
        lenient().when(historyAssembler.assemble(any(), anyInt(), any())).thenReturn(RunHistory.empty());
        ConnectorRegistry registry = new ConnectorRegistry(List.of(memoryHandler, timeHandler));
        ConnectorEnvFactory envFactory = new ConnectorEnvFactory(null, null);
        catalog = new RunCatalog(agentRunRepository, agentRepository, agentSkillRepository, agentSkillService,
                skillRepository, connectionRepository, connectorRepository, connectionToolRepository,
                registry, envFactory, channelRepository, channelHandlerRegistry);
        service = new RunContextService(catalog, agentRepository, agenticTeamRepository,
                registry, envFactory, channelRepository, channelHandlerRegistry,
                inboundTextResolver, historyAssembler,
                // Язык-первоисточник: переводов нет, блоки промпта совпадают с константами в коде.
                new PromptTexts(new ContentProperties()));
    }

    private Agent agent() {
        return Agent.builder().id(AGENT_ID).userId(USER_ID).name("Bot")
                .instructions("You are helpful.").enabled(true).build();
    }

    private TriggerLog triggerLog(String connectorCode, String name) {
        return triggerLog(connectorCode, name, Map.of("text", "hello agent"));
    }

    private TriggerLog triggerLog(String connectorCode, String name, Map<String, Object> input) {
        return TriggerLog.builder()
                .connectorCode(connectorCode)
                .connectionId(CONNECTION_ID.toString())
                .externalId("evt-1")
                .name(name)
                .input(input)
                .build();
    }

    private AgentRun run(Agent agent, TriggerLog log, Channels channels) {
        return AgentRun.builder()
                .agent(agent)
                .triggerLog(log)
                .destination("GENERIC")
                .channels(ChannelsCodec.toMap(channels))
                .build();
    }

    private void stubRun(AgentRun run) {
        when(agentRunRepository.findById(TRIGGER_ID)).thenReturn(Optional.of(run));
        // The assembly re-reads the agent by id once the catalogue has checked the run belongs to it.
        lenient().when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(run.getAgent()));
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
        when(agentSkillService.resolveSkills(anyList())).thenReturn(
                skills.stream().collect(java.util.stream.Collectors.toMap(
                        AgentSkillWithConnectorsResponse::skillId, s -> s)));
        // Every stubbed skill counts as satisfied and points at the connection of its code — the
        // satisfaction rules themselves are tested in AgentSkillServiceTest.
        when(agentSkillService.gate(AGENT_ID)).thenReturn(new AgentSkillService.SkillGate(
                skills.stream().collect(java.util.stream.Collectors.toMap(
                        AgentSkillWithConnectorsResponse::skillId, s -> java.util.Set.of(CONNECTION_ID))),
                List.of()));
    }

    private Connection memoryConnection() {
        return Connection.builder()
                .id(CONNECTION_ID)
                .userId(USER_ID)
                .connectorCode("persist-memory")
                .build();
    }

    @Nested
    @DisplayName("SYSTEM_TRIGGER (direct-ран)")
    class SystemTrigger {

        @Test
        @DisplayName("guidance + untrusted event-блок; тела — только подошедших скиллов")
        void buildsTriggerContext() {
            Agent agent = agent();
            stubRun(run(agent, triggerLog("time", "due"), null));
            UUID timeSkill = UUID.randomUUID();
            UUID otherSkill = UUID.randomUUID();
            stubSkills(List.of(
                    new AgentSkillWithConnectorsResponse(timeSkill, "Reminders", "d1", List.of("time"), Disclosure.EAGER),
                    new AgentSkillWithConnectorsResponse(otherSkill, "Boards", "d2", List.of("board"), Disclosure.EAGER)));
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

            // Тулы: связей нет — пусто.
            assertTrue(view.tools().isEmpty());
        }

        @Test
        @DisplayName("тулы собираются от всех скиллов агента, не только подошедших триггеру")
        void toolsFromAllListedSkills() {
            Agent agent = agent();
            // Триггер от board; единственный скилл агента требует persist-memory — не матчится.
            stubRun(run(agent, triggerLog("board", "task_comment_created"), null));
            stubSkills(List.of(new AgentSkillWithConnectorsResponse(
                    UUID.randomUUID(), "Memory", "d", List.of("persist-memory"), Disclosure.EAGER)));
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID))
                    .thenReturn(List.of(memoryConnection()));
            lenient().when(memoryHandler.promptBlocks(any(ConnectorEnv.class))).thenReturn(List.of());
            Connector connector = new Connector();
            connector.setCode("persist-memory");
            connector.setDefinitionBinding(DefinitionBinding.STATIC);
            when(connectorRepository.findById("persist-memory")).thenReturn(Optional.of(connector));
            when(memoryHandler.getTools(any(ConnectorEnv.class))).thenReturn(Map.of(
                    "get_memory", new ConnectorToolSpec("get_memory", null, "d", null, null, null, null, null)));

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            // Тул непрофильного скилла доступен (задача с доски может требовать любой скилл)...
            assertEquals(1, view.tools().size());
            assertEquals("persist-memory", view.tools().get(0).connectorCode());
            // ...а его тело в промпт не попало — по триггеру скоупятся только тела.
            List<String> names = view.systemBlocks().stream().map(RunBlock::name).toList();
            assertFalse(names.contains("skill"));
        }
    }

    @Nested
    @DisplayName("Директивы контекста (ContextDirectives)")
    class Directives {

        private void declareDue(ContextDirectives directives) {
            when(timeHandler.getTriggers()).thenReturn(Map.of("due", new ru.agimate.controlapi
                    .connectors.core.dto.TriggerSpec("desc", List.of("prompt"), directives)));
        }

        private void stubTimeConnection() {
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of(
                    Connection.builder().id(CONNECTION_ID).userId(USER_ID).connectorCode("time").build()));
            Connector connector = new Connector();
            connector.setCode("time");
            connector.setDefinitionBinding(DefinitionBinding.STATIC);
            when(connectorRepository.findById("time")).thenReturn(Optional.of(connector));
        }

        @Test
        @DisplayName("PROMPT — trusted trigger_prompt из data + event_guidance перед ним, event-блока нет")
        void promptPresentation() {
            stubRun(run(agent(), triggerLog("time", "due", Map.of("prompt", "Проверь заказ №42")), null));
            stubSkills(List.of());
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of());
            declareDue(ContextDirectives.builder()
                    .presentation(ContextDirectives.Presentation.PROMPT)
                    .promptParam("prompt")
                    .guidance("Это твоя отложенная задача.")
                    .build());

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            List<RunBlock> user = view.userBlocks();
            RunBlock main = user.get(user.size() - 1);
            assertEquals("trigger_prompt", main.name());
            assertTrue(main.trusted());
            assertEquals("Проверь заказ №42", main.content());
            assertEquals("time", main.attrs().get("connector"));
            RunBlock guidance = user.get(user.size() - 2);
            assertEquals("event_guidance", guidance.name());
            assertTrue(guidance.trusted());
            assertTrue(view.userBlocks().stream().noneMatch(b -> b.name().equals("event")));
            // Автономный режим не меняется: trigger_guidance в system остаётся.
            assertTrue(view.systemBlocks().stream().anyMatch(b -> b.name().equals("trigger_guidance")));
        }

        @Test
        @DisplayName("PROMPT без пригодного параметра — фолбэк на untrusted event")
        void promptFallsBackToEvent() {
            stubRun(run(agent(), triggerLog("time", "due", Map.of("other", "x")), null));
            stubSkills(List.of());
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of());
            declareDue(ContextDirectives.builder()
                    .presentation(ContextDirectives.Presentation.PROMPT)
                    .promptParam("prompt")
                    .build());

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            RunBlock main = view.userBlocks().get(view.userBlocks().size() - 1);
            assertEquals("event", main.name());
            assertFalse(main.trusted());
        }

        @Test
        @DisplayName("ownConnectionTools подтягивает тулы connection события без скиллов")
        void ownConnectionTools() {
            stubRun(run(agent(), triggerLog("time", "due", Map.of("prompt", "п")), null));
            stubSkills(List.of());
            stubTimeConnection();
            when(timeHandler.getTools(any(ConnectorEnv.class))).thenReturn(Map.of(
                    "cancel_scheduled",
                    new ConnectorToolSpec("cancel_scheduled", null, "d", null, null, null, null, null)));
            declareDue(ContextDirectives.builder().ownConnectionTools(true).build());

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            assertEquals(1, view.tools().size());
            assertEquals("time", view.tools().get(0).connectorCode());
            assertEquals(CONNECTION_ID.toString(), view.tools().get(0).connectionId());
        }

        @Test
        @DisplayName("коннекция события со сломанной авторизацией тулы всё равно отдаёт")
        void ownConnectionToolsSurviveBrokenAuth() {
            stubRun(run(agent(), triggerLog("time", "due", Map.of("prompt", "п")), null));
            stubSkills(List.of());
            Connection expired = Connection.builder().id(CONNECTION_ID).userId(USER_ID)
                    .connectorCode("time").authStatus(ConnectionAuthStatus.AUTH_EXPIRED).build();
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of(expired));
            Connector connector = new Connector();
            connector.setCode("time");
            connector.setDefinitionBinding(DefinitionBinding.STATIC);
            when(connectorRepository.findById("time")).thenReturn(Optional.of(connector));
            when(timeHandler.getTools(any(ConnectorEnv.class))).thenReturn(Map.of(
                    "cancel_scheduled",
                    new ConnectorToolSpec("cancel_scheduled", null, "d", null, null, null, null, null)));
            declareDue(ContextDirectives.builder().ownConnectionTools(true).build());

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            // Мимо гейта навыков причину сказать нечем, а вызов вернёт ссылку на переавторизацию —
            // спрятать тул значит спрятать единственное, что чинит ситуацию
            assertEquals(1, view.tools().size());
        }

        @Test
        @DisplayName("skillTools=false отключает тулы скиллов агента")
        void skillToolsOff() {
            stubRun(run(agent(), triggerLog("time", "due", Map.of("prompt", "п")), null));
            stubSkills(List.of(new AgentSkillWithConnectorsResponse(
                    UUID.randomUUID(), "Memory", "d", List.of("persist-memory"), Disclosure.EAGER)));
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID))
                    .thenReturn(List.of(memoryConnection()));
            lenient().when(memoryHandler.promptBlocks(any(ConnectorEnv.class))).thenReturn(List.of());
            declareDue(ContextDirectives.builder().skillTools(false).build());

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            assertTrue(view.tools().isEmpty());
        }

        @Test
        @DisplayName("historyLimit=0 — история не загружается даже при живой сессии")
        void historyLimitZero() {
            Channels answerOnly = new Channels(null, null, new ChannelInfo(CHANNEL_ID, SESSION_ID, null));
            AgentRun run = run(agent(), triggerLog("time", "due", Map.of("prompt", "п")), answerOnly);
            run.setSessionId(SESSION_ID);
            stubRun(run);
            stubSkills(List.of());
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of());
            declareDue(ContextDirectives.builder().historyLimit(0).build());

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            assertTrue(view.history().isEmpty());
            org.mockito.Mockito.verify(historyAssembler)
                    .assemble(SESSION_ID, 0, ContextSpec.SYSTEM_TRIGGER.historyParts());
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
                    UUID.randomUUID(), "Memory", "d", List.of("persist-memory"), Disclosure.EAGER)));

            when(inboundTextResolver.resolve(any(), any()))
                    .thenReturn(Optional.of(InboundMessage.text("hello agent")));

            // memory-коннектор привязан: system-блок memory + ephemeral user-блок notes + тул.
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID))
                    .thenReturn(List.of(memoryConnection()));
            when(memoryHandler.promptBlocks(any(ConnectorEnv.class))).thenReturn(List.of(
                    PromptBlock.system("memory", "facts", Map.of("version", "3")),
                    PromptBlock.user("memory_notes", "- note")));
            Connector connector = new Connector();
            connector.setCode("persist-memory");
            connector.setDefinitionBinding(DefinitionBinding.STATIC);
            when(connectorRepository.findById("persist-memory")).thenReturn(Optional.of(connector));
            when(memoryHandler.getTools(any(ConnectorEnv.class))).thenReturn(Map.of(
                    "get_memory", new ConnectorToolSpec("get_memory", null, "d", null, null, null, null, null)));

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            List<String> systemNames = view.systemBlocks().stream().map(RunBlock::name).toList();
            assertTrue(systemNames.contains("memory"));
            assertFalse(systemNames.contains("trigger_guidance"));
            // The instructions open the prompt, the agent's metadata follows.
            assertEquals("You are helpful.", view.systemBlocks().get(0).content());
            assertEquals("agent", systemNames.get(1));

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
            assertEquals("persist-memory__" + tool.spec().name(), tool.llmName());
        }

        @Test
        @DisplayName("тела ВСЕХ скиллов агента инжектятся в диалог (SkillBodies.ALL)")
        void allSkillBodiesLoaded() {
            Agent agent = agent();
            Channels channels = Channels.ofPrompt(new ChannelInfo(CHANNEL_ID, SESSION_ID, null));
            stubRun(run(agent, triggerLog("webchat", "message_received"), channels));
            UUID mediaSkill = UUID.randomUUID();
            // Скилл media никак не связан с коннектором диалога (webchat) — тело всё равно грузится.
            stubSkills(List.of(new AgentSkillWithConnectorsResponse(
                    mediaSkill, "Media", "d", List.of("media"), Disclosure.EAGER)));
            when(skillRepository.findByIdNotDeleted(mediaSkill)).thenReturn(Optional.of(
                    ru.agimate.controlapi.database.entities.Skill.builder()
                            .id(mediaSkill).name("Media").mdContent("Iteration discipline").version(1).build()));
            when(inboundTextResolver.resolve(any(), any()))
                    .thenReturn(Optional.of(InboundMessage.text("hello")));
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of());

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            assertTrue(view.systemBlocks().stream().anyMatch(b ->
                    "skill".equals(b.name()) && b.content().contains("Iteration discipline")));
        }

        @Test
        @DisplayName("отсечённый навык остаётся строкой каталога с причиной, но без тела")
        void withheldSkillIsListedWithItsReason() {
            Agent agent = agent();
            Channels channels = Channels.ofPrompt(new ChannelInfo(CHANNEL_ID, SESSION_ID, null));
            stubRun(run(agent, triggerLog("webchat", "message_received"), channels));
            UUID blocked = UUID.randomUUID();
            stubSkills(List.of());
            when(agentSkillService.gate(AGENT_ID)).thenReturn(new AgentSkillService.SkillGate(Map.of(),
                    List.of(new AgentSkillService.WithheldSkill(blocked, "sales-report", "Недельный отчёт",
                            List.of("sheets"),
                            List.of(new AgentSkillService.WithheldSkill.Blocker("sheets", "sheets",
                                    AgentSkillService.RequirementState.NOT_CHOSEN))))));
            when(inboundTextResolver.resolve(any(), any()))
                    .thenReturn(Optional.of(InboundMessage.text("hello")));
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of());

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            RunBlock skills = view.systemBlocks().stream()
                    .filter(b -> "skills".equals(b.name())).findFirst().orElseThrow();
            assertTrue(skills.content().contains("name: sales-report"));
            assertTrue(skills.content().contains("status: unavailable"));
            assertTrue(skills.content().contains("blocked_by: sheets (sheets) — NOT_CHOSEN"));
            // Пояснение — отдельным блоком, как все прочие правила поведения: внутри списка
            // «ключ: значение» абзац читался бы продолжением последней записи
            assertFalse(skills.content().contains(RunContextService.SKILLS_UNAVAILABLE_GUIDANCE));
            assertTrue(view.systemBlocks().stream().anyMatch(b ->
                            "skills_unavailable_guidance".equals(b.name())
                                    && b.content().equals(RunContextService.SKILLS_UNAVAILABLE_GUIDANCE)),
                    "без пояснения модель не знает, что значит unavailable");
            // Тело не едет — ради этого гейт и существует; едет только причина
            assertTrue(view.systemBlocks().stream().noneMatch(b -> "skill".equals(b.name())));
        }

        @Test
        @DisplayName("prompt-канал с contributesPromptTools подмешивает тулы своего коннектора без скилла")
        void promptChannelContributesTools() {
            Agent agent = agent();
            Channels channels = Channels.ofPrompt(new ChannelInfo(CHANNEL_ID, SESSION_ID, null));
            stubRun(run(agent, triggerLog("acp", "message_received"), channels));
            stubSkills(List.of()); // ни один скилл не требует коннектор

            when(inboundTextResolver.resolve(any(), any()))
                    .thenReturn(Optional.of(InboundMessage.text("hi")));
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID))
                    .thenReturn(List.of(memoryConnection()));
            org.mockito.Mockito.lenient().when(memoryHandler.promptBlocks(any(ConnectorEnv.class)))
                    .thenReturn(List.of());

            // Канал приносит тулы: handler contributesPromptTools, connectorCode == персист-мемори.
            ru.agimate.controlapi.database.entities.Channel channel =
                    ru.agimate.controlapi.database.entities.Channel.builder()
                            .id(CHANNEL_ID).channelHandler("acp").connectorCode("persist-memory")
                            // channels.connection_id is NOT NULL — the gate matches the instance, not the code
                            .connectionId(CONNECTION_ID).build();
            when(channelRepository.findByIdAndDeletedAtIsNull(CHANNEL_ID)).thenReturn(Optional.of(channel));
            ru.agimate.controlapi.service.channel.handler.ChannelHandler h =
                    mock(ru.agimate.controlapi.service.channel.handler.ChannelHandler.class);
            when(h.contributesPromptTools()).thenReturn(true);
            when(channelHandlerRegistry.find("acp")).thenReturn(Optional.of(h));

            Connector connector = new Connector();
            connector.setCode("persist-memory");
            connector.setDefinitionBinding(DefinitionBinding.STATIC);
            when(connectorRepository.findById("persist-memory")).thenReturn(Optional.of(connector));
            when(memoryHandler.getTools(any(ConnectorEnv.class))).thenReturn(Map.of(
                    "get_memory", new ConnectorToolSpec("get_memory", null, "d", null, null, null, null, null)));

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            assertEquals(1, view.tools().size());
            assertEquals("persist-memory", view.tools().get(0).connectorCode());
        }

        @Test
        @DisplayName("сессионные тулы не попадают в чужой ран, хотя скилл требует их коннектор")
        void sessionScopedToolsStayOutOfForeignRun() {
            Agent agent = agent();
            Channels channels = Channels.ofPrompt(new ChannelInfo(CHANNEL_ID, SESSION_ID, null));
            stubRun(run(agent, triggerLog("webchat", "message_received"), channels));
            UUID ideSkill = UUID.randomUUID();
            stubSkills(List.of(new AgentSkillWithConnectorsResponse(
                    ideSkill, "IDE", "d", List.of("persist-memory"), Disclosure.EAGER)));
            when(skillRepository.findByIdNotDeleted(ideSkill)).thenReturn(Optional.of(
                    ru.agimate.controlapi.database.entities.Skill.builder()
                            .id(ideSkill).name("IDE").mdContent("Working from the IDE").version(1).build()));

            when(inboundTextResolver.resolve(any(), any()))
                    .thenReturn(Optional.of(InboundMessage.text("hi")));
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID))
                    .thenReturn(List.of(memoryConnection()));
            lenient().when(memoryHandler.promptBlocks(any(ConnectorEnv.class))).thenReturn(List.of());

            // Промпт-канал — чужой (webchat, тулов не приносит): sessionAwareConnectionId остаётся null.
            ru.agimate.controlapi.database.entities.Channel channel =
                    ru.agimate.controlapi.database.entities.Channel.builder()
                            .id(CHANNEL_ID).channelHandler("webchat").connectorCode("webchat")
                            .connectionId(UUID.randomUUID()).build();
            when(channelRepository.findByIdAndDeletedAtIsNull(CHANNEL_ID)).thenReturn(Optional.of(channel));
            ru.agimate.controlapi.service.channel.handler.ChannelHandler h =
                    mock(ru.agimate.controlapi.service.channel.handler.ChannelHandler.class);
            when(h.contributesPromptTools()).thenReturn(false);
            when(channelHandlerRegistry.find("webchat")).thenReturn(Optional.of(h));

            Connector connector = new Connector();
            connector.setCode("persist-memory");
            connector.setDefinitionBinding(DefinitionBinding.STATIC);
            when(connectorRepository.findById("persist-memory")).thenReturn(Optional.of(connector));
            when(memoryHandler.sessionScopedTools()).thenReturn(true);

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            assertTrue(view.tools().isEmpty());
            // Гейт снимает только тулы: скилл в контексте остаётся, привязка-то есть.
            assertTrue(view.systemBlocks().stream().anyMatch(b ->
                    "skill".equals(b.name()) && b.content().contains("Working from the IDE")));
        }
    }

    @Nested
    @DisplayName("История")
    class History {

        @Test
        @DisplayName("окно и части истории уходят сборщику, его ответ — в контекст")
        void delegatesToAssembler() {
            Agent agent = agent();
            Channels answerOnly = new Channels(null, null, new ChannelInfo(CHANNEL_ID, SESSION_ID, null));
            AgentRun run = run(agent, triggerLog("time", "due"), answerOnly);
            run.setSessionId(SESSION_ID);
            stubRun(run);
            stubSkills(List.of());
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of());
            RunHistoryMessage answer = new RunHistoryMessage(ChannelSessionMessageKind.ANSWER, "old answer");
            when(historyAssembler.assemble(SESSION_ID, EffectiveContext.DEFAULT_HISTORY_LIMIT,
                    ContextSpec.SYSTEM_TRIGGER.historyParts()))
                    .thenReturn(new RunHistory(List.of(answer), List.of(), RunHistoryAssembler.DISCLOSED_BUDGET_BYTES));

            assertEquals(List.of(answer), service.build(AGENT_ID, TRIGGER_ID).history());
        }

        @Test
        @DisplayName("у рана без каналов истории нет: сессия коннекшена в контекст не тянется")
        void connectionSessionCarriesNoHistory() {
            AgentRun run = run(agent(), triggerLog("board", "task_changed"), null);
            run.setSessionId(SESSION_ID);
            stubRun(run);
            stubSkills(List.of());
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of());

            assertTrue(service.build(AGENT_ID, TRIGGER_ID).history().isEmpty());
            org.mockito.Mockito.verify(historyAssembler)
                    .assemble(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.anyInt(),
                            org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested
    @DisplayName("Валидация")
    class Validation {

        @Test
        @DisplayName("неизвестный trigger_id → NotFound")
        void unknownRun() {
            when(agentRunRepository.findById(TRIGGER_ID)).thenReturn(Optional.empty());
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

    @Nested
    @DisplayName("Постепенное раскрытие")
    class ProgressiveDisclosure {

        private static final UUID LOADER_CONNECTION_ID = UUID.randomUUID();
        private final UUID memorySkill = UUID.randomUUID();
        private final UUID loaderSkill = UUID.randomUUID();

        private Connection loaderConnection(String code) {
            return Connection.builder().id(LOADER_CONNECTION_ID).userId(USER_ID).connectorCode(code).build();
        }

        /** A memory skill (its connector LAZY) and, optionally, a loader skill bringing the loader connection. */
        private void stubScope(String loaderCode, Disclosure memorySkillAxis) {
            List<AgentSkillWithConnectorsResponse> skills = new ArrayList<>();
            skills.add(new AgentSkillWithConnectorsResponse(memorySkill, "Memory", "d", List.of("persist-memory"), memorySkillAxis));
            Map<UUID, java.util.Set<UUID>> satisfied = new java.util.HashMap<>();
            satisfied.put(memorySkill, java.util.Set.of(CONNECTION_ID));
            List<Connection> connections = new ArrayList<>(List.of(memoryConnection()));
            if (loaderCode != null) {
                skills.add(new AgentSkillWithConnectorsResponse(loaderSkill, loaderCode, "d", List.of(loaderCode), Disclosure.EAGER));
                satisfied.put(loaderSkill, java.util.Set.of(LOADER_CONNECTION_ID));
                connections.add(loaderConnection(loaderCode));
                Connector loader = new Connector();
                loader.setCode(loaderCode);
                loader.setDefinitionBinding(DefinitionBinding.STATIC);
                lenient().when(connectorRepository.findById(loaderCode)).thenReturn(Optional.of(loader));
            }
            List<AgentSkill> refs = skills.stream().map(sk -> {
                AgentSkill ref = new AgentSkill();
                ref.setSkillId(sk.skillId());
                return ref;
            }).toList();
            when(agentSkillRepository.findByAgentId(AGENT_ID)).thenReturn(refs);
            when(agentSkillService.resolveSkills(anyList())).thenReturn(skills.stream()
                    .collect(java.util.stream.Collectors.toMap(AgentSkillWithConnectorsResponse::skillId, sk -> sk)));
            when(agentSkillService.gate(AGENT_ID)).thenReturn(
                    new AgentSkillService.SkillGate(satisfied, List.of()));
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(connections);
            lenient().when(memoryHandler.promptBlocks(any(ConnectorEnv.class))).thenReturn(List.of());
            Connector memory = new Connector();
            memory.setCode("persist-memory");
            memory.setDefinitionBinding(DefinitionBinding.STATIC);
            memory.setDisclosure(Disclosure.LAZY);
            when(connectorRepository.findById("persist-memory")).thenReturn(Optional.of(memory));
            when(memoryHandler.getTools(any(ConnectorEnv.class))).thenReturn(Map.of(
                    "get_memory", new ConnectorToolSpec("get_memory", null, "Get memory. With detail.",
                            ru.agimate.controlapi.connectors.core.dto.JsonSchema.object(Map.of(), null, null),
                            null, null, null, null)));
            lenient().when(skillRepository.findByIdNotDeleted(memorySkill)).thenReturn(Optional.of(
                    ru.agimate.controlapi.database.entities.Skill.builder().id(memorySkill).name("Memory")
                            .mdContent("memory body").build()));
            lenient().when(skillRepository.findByIdNotDeleted(loaderSkill)).thenReturn(Optional.of(
                    ru.agimate.controlapi.database.entities.Skill.builder().id(loaderSkill).name("loader")
                            .mdContent("loader body").build()));
        }

        private void stubDialogue() {
            Channels channels = Channels.ofPrompt(new ChannelInfo(CHANNEL_ID, SESSION_ID, null));
            stubRun(run(agent(), triggerLog("webchat", "message_received"), channels));
            when(inboundTextResolver.resolve(any(), any())).thenReturn(Optional.of(InboundMessage.text("hi")));
        }

        private RunTool memoryTool(RunContextView view) {
            return view.tools().stream().filter(t -> t.connectorCode().equals("persist-memory")).findFirst().orElseThrow();
        }

        @Test
        @DisplayName("без tool-loader ось не читается: LAZY-коннектор едет EAGER, листинга нет")
        void eagerWithoutLoader() {
            stubDialogue();
            stubScope(null, Disclosure.EAGER);

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            assertEquals(Disclosure.EAGER, memoryTool(view).disclosure());
            assertTrue(view.systemBlocks().stream().noneMatch(b -> b.name().equals("deferred_tools")));
        }

        @Test
        @DisplayName("с tool-loader LAZY-тул едет вывеской: summary из первой фразы, строка в deferred_tools")
        void lazyWithLoader() {
            stubDialogue();
            stubScope(RunCatalog.TOOL_LOADER, Disclosure.EAGER);

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            RunTool tool = memoryTool(view);
            assertEquals(Disclosure.LAZY, tool.disclosure());
            assertEquals("Get memory.", tool.summary());
            RunBlock listing = view.systemBlocks().stream().filter(b -> b.name().equals("deferred_tools")).findFirst().orElseThrow();
            assertEquals("- persist-memory__get_memory: Get memory.", listing.content());
        }

        @Test
        @DisplayName("тул, раскрытый в окне истории, едет EAGER и из листинга уходит")
        void disclosedByHistory() {
            stubDialogue();
            stubScope(RunCatalog.TOOL_LOADER, Disclosure.EAGER);
            when(historyAssembler.assemble(any(), anyInt(), any())).thenReturn(
                    new RunHistory(List.of(), List.of("persist-memory__get_memory"), RunHistoryAssembler.DISCLOSED_BUDGET_BYTES));

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            assertEquals(Disclosure.EAGER, memoryTool(view).disclosure());
            assertTrue(view.systemBlocks().stream().noneMatch(b -> b.name().equals("deferred_tools")));
        }

        @Test
        @DisplayName("бюджет из истории исчерпан — раскрытие не применяется")
        void budgetExhausted() {
            stubDialogue();
            stubScope(RunCatalog.TOOL_LOADER, Disclosure.EAGER);
            when(historyAssembler.assemble(any(), anyInt(), any())).thenReturn(
                    new RunHistory(List.of(), List.of("persist-memory__get_memory"), 0));

            assertEquals(Disclosure.LAZY, memoryTool(service.build(AGENT_ID, TRIGGER_ID)).disclosure());
        }

        @Test
        @DisplayName("триггерный ран раскрывает тулы коннектора события наперёд, независимо от оси")
        void triggerRunDisclosesEventConnectorUpfront() {
            stubRun(run(agent(), triggerLog("persist-memory", "consolidate"), null));
            stubScope(RunCatalog.TOOL_LOADER, Disclosure.EAGER);

            assertEquals(Disclosure.EAGER, memoryTool(service.build(AGENT_ID, TRIGGER_ID)).disclosure());
        }

        @Test
        @DisplayName("с skill-loader тело LAZY-навыка не едет, в листинге навыков он помечен; без — едет как раньше")
        void lazySkillBody() {
            stubDialogue();
            stubScope(RunCatalog.SKILL_LOADER, Disclosure.LAZY);

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            List<String> bodies = view.systemBlocks().stream().filter(b -> b.name().equals("skill")).map(RunBlock::content).toList();
            assertEquals(List.of("loader body"), bodies);
            RunBlock skills = view.systemBlocks().stream().filter(b -> b.name().equals("skills")).findFirst().orElseThrow();
            assertTrue(skills.content().contains("  name: Memory\n  description: d\n  connector_codes: persist-memory\n  disclosure: lazy"));
            // The tool axis is untouched by the skill loader: the memory tool stays EAGER.
            assertEquals(Disclosure.EAGER, memoryTool(view).disclosure());
        }

        @Test
        @DisplayName("без skill-loader тело LAZY-навыка едет, и листинг навыков без пометки")
        void lazySkillBodyWithoutLoader() {
            stubDialogue();
            stubScope(null, Disclosure.LAZY);

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            List<String> bodies = view.systemBlocks().stream().filter(b -> b.name().equals("skill")).map(RunBlock::content).toList();
            assertEquals(List.of("memory body"), bodies);
            RunBlock skills = view.systemBlocks().stream().filter(b -> b.name().equals("skills")).findFirst().orElseThrow();
            assertFalse(skills.content().contains("disclosure"));
        }

        @Test
        @DisplayName("триггерный ран: тело подошедшего LAZY-навыка едет наперёд даже при skill-loader")
        void triggerRunDisclosesMatchedBodyUpfront() {
            stubRun(run(agent(), triggerLog("persist-memory", "consolidate"), null));
            stubScope(RunCatalog.SKILL_LOADER, Disclosure.LAZY);

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            List<String> bodies = view.systemBlocks().stream().filter(b -> b.name().equals("skill")).map(RunBlock::content).toList();
            assertEquals(List.of("memory body"), bodies);
        }
    }
}
