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
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.ChannelSessionMessage;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.TriggerLog;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.enums.DefinitionBinding;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.AgenticTeamRepository;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.service.AgentSkillService;
import ru.agimate.controlapi.service.channel.InboundTextResolver;
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
    @Mock private ChannelSessionMessageRepository messageRepository;
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
    private RunContextService service;

    @BeforeEach
    void setUp() {
        memoryHandler = mock(MemoryLikeHandler.class);
        lenient().when(memoryHandler.connectorCode()).thenReturn("persist-memory");
        timeHandler = mock(TimeLikeHandler.class);
        lenient().when(timeHandler.connectorCode()).thenReturn("time");
        ConnectorRegistry registry = new ConnectorRegistry(List.of(memoryHandler, timeHandler));
        service = new RunContextService(agentRunRepository, agentRepository,
                agenticTeamRepository, agentSkillRepository, agentSkillService, skillRepository,
                connectionRepository, connectorRepository, connectionToolRepository,
                registry, new ConnectorEnvFactory(null, null), channelRepository, channelHandlerRegistry,
                inboundTextResolver, messageRepository);
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
                    UUID.randomUUID(), "Memory", "d", List.of("persist-memory"))));
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
        @DisplayName("skillTools=false отключает тулы скиллов агента")
        void skillToolsOff() {
            stubRun(run(agent(), triggerLog("time", "due", Map.of("prompt", "п")), null));
            stubSkills(List.of(new AgentSkillWithConnectorsResponse(
                    UUID.randomUUID(), "Memory", "d", List.of("persist-memory"))));
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
            AgentRun run = run(agent(), triggerLog("time", "due", Map.of("prompt", "п")), null);
            run.setSessionId(SESSION_ID);
            stubRun(run);
            stubSkills(List.of());
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of());
            declareDue(ContextDirectives.builder().historyLimit(0).build());

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            assertTrue(view.history().isEmpty());
            org.mockito.Mockito.verify(messageRepository, org.mockito.Mockito.never())
                    .findBySessionIdAndCompletedTrueOrderByIdDesc(any(), any());
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

        @Test
        @DisplayName("тела ВСЕХ скиллов агента инжектятся в диалог (SkillBodies.ALL)")
        void allSkillBodiesLoaded() {
            Agent agent = agent();
            Channels channels = Channels.ofPrompt(new ChannelInfo(CHANNEL_ID, SESSION_ID, null));
            stubRun(run(agent, triggerLog("webchat", "message_received"), channels));
            UUID mediaSkill = UUID.randomUUID();
            // Скилл media никак не связан с коннектором диалога (webchat) — тело всё равно грузится.
            stubSkills(List.of(new AgentSkillWithConnectorsResponse(
                    mediaSkill, "Media", "d", List.of("media"))));
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
                            .id(CHANNEL_ID).channelHandler("acp").connectorCode("persist-memory").build();
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
    }

    @Nested
    @DisplayName("История")
    class History {

        private ChannelSessionMessage msg(ChannelSessionMessageKind kind, String text, String progressType) {
            ChannelSessionMessage m = new ChannelSessionMessage();
            m.setSessionId(SESSION_ID);
            m.setAgentId(AGENT_ID);
            m.setRunId(UUID.randomUUID());
            m.setKind(kind);
            m.setMessage(text);
            m.setProgressType(progressType);
            m.setCompleted(true);
            return m;
        }

        @Test
        @DisplayName("хвост разворачивается, старые kinds маппятся на v2, thinking-строки отфильтрованы (NO_REASONING)")
        void mapsHistory() {
            Agent agent = agent();
            AgentRun run = run(agent, triggerLog("time", "due"), null);
            run.setSessionId(SESSION_ID);
            stubRun(run);
            stubSkills(List.of());
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of());
            // Репозиторий отдаёт хвост новыми-первыми; сервис разворачивает в хронологию.
            when(messageRepository.findBySessionIdAndCompletedTrueOrderByIdDesc(eq(SESSION_ID), any()))
                    .thenReturn(List.of(
                            msg(ChannelSessionMessageKind.ANSWER, "ok, done", null),
                            msg(ChannelSessionMessageKind.PROGRESS, "🔧 get_tasks", "TOOL_CALL"),
                            msg(ChannelSessionMessageKind.PROGRESS, "💭 thinking...", "THINKING"),
                            msg(ChannelSessionMessageKind.RESPONSE, "old answer", null),
                            msg(ChannelSessionMessageKind.REQUEST, "old question", null)));

            RunContextView view = service.build(AGENT_ID, TRIGGER_ID);

            assertEquals(List.of(
                    new RunHistoryMessage(ChannelSessionMessageKind.INBOUND, "old question"),
                    new RunHistoryMessage(ChannelSessionMessageKind.ANSWER, "old answer"),
                    // Легаси 🔧-строка без message_json санитизируется в констатацию.
                    new RunHistoryMessage(ChannelSessionMessageKind.PROGRESS, "[вызван инструмент get_tasks]"),
                    new RunHistoryMessage(ChannelSessionMessageKind.ANSWER, "ok, done")),
                    view.history());
        }

        @Test
        @DisplayName("tool_turn из message_json уходит структурно; TEXT-преамбула того же рана скипается")
        void structuredToolTurn() {
            Agent agent = agent();
            AgentRun run = run(agent, triggerLog("time", "due"), null);
            run.setSessionId(SESSION_ID);
            stubRun(run);
            stubSkills(List.of());
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of());

            UUID toolRunId = UUID.randomUUID();
            ChannelSessionMessage toolMsg = msg(ChannelSessionMessageKind.PROGRESS, "🔧 get_tasks", "TOOL_CALL");
            toolMsg.setRunId(toolRunId);
            toolMsg.setMessageJson(Map.of(
                    "text", "смотрю доску",
                    "calls", List.of(Map.of("id", "c1", "name", "board.get_tasks",
                            "argumentsJson", "{\"boardId\":1}")),
                    "results", List.of(Map.of("id", "c1", "name", "board.get_tasks",
                            "outputJson", "{\"tasks\":[]}", "failed", false))));
            ChannelSessionMessage preamble = msg(ChannelSessionMessageKind.PROGRESS, "смотрю доску", "TEXT");
            preamble.setRunId(toolRunId);
            ChannelSessionMessage answer = msg(ChannelSessionMessageKind.ANSWER, "готово", null);
            answer.setRunId(toolRunId);
            when(messageRepository.findBySessionIdAndCompletedTrueOrderByIdDesc(eq(SESSION_ID), any()))
                    .thenReturn(List.of(answer, toolMsg, preamble)); // новые первыми

            List<RunHistoryMessage> history = service.build(AGENT_ID, TRIGGER_ID).history();

            assertEquals(2, history.size()); // TEXT-преамбула скипнута
            RunHistoryMessage turn = history.get(0);
            assertEquals("смотрю доску", turn.toolTurn().text());
            assertEquals("board.get_tasks", turn.toolTurn().calls().get(0).name());
            assertEquals("{\"tasks\":[]}", turn.toolTurn().results().get(0).outputJson());
            assertEquals("готово", history.get(1).text());
        }

        @Test
        @DisplayName("v2.1a: раздельные TOOL_CALL (calls) и TOOL_RESULT (results, пустой текст) отдаются двумя записями")
        void splitToolRows() {
            Agent agent = agent();
            AgentRun run = run(agent, triggerLog("time", "due"), null);
            run.setSessionId(SESSION_ID);
            stubRun(run);
            stubSkills(List.of());
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of());

            UUID toolRunId = UUID.randomUUID();
            ChannelSessionMessage callsMsg = msg(ChannelSessionMessageKind.PROGRESS, "🔧 get_tasks", "TOOL_CALL");
            callsMsg.setRunId(toolRunId);
            callsMsg.setMessageJson(Map.of(
                    "text", "смотрю доску",
                    "calls", List.of(Map.of("id", "c1", "name", "board.get_tasks",
                            "argumentsJson", "{\"boardId\":1}")),
                    "results", List.of()));
            // results-строка: пустой текст, results в message_json — не должна быть скипнута blank-гардом.
            ChannelSessionMessage resultsMsg = msg(ChannelSessionMessageKind.PROGRESS, "", "TOOL_RESULT");
            resultsMsg.setRunId(toolRunId);
            resultsMsg.setMessageJson(Map.of(
                    "calls", List.of(),
                    "results", List.of(Map.of("id", "c1", "name", "board.get_tasks",
                            "outputJson", "{\"tasks\":[]}", "failed", false))));
            when(messageRepository.findBySessionIdAndCompletedTrueOrderByIdDesc(eq(SESSION_ID), any()))
                    .thenReturn(List.of(resultsMsg, callsMsg)); // новые первыми → развернётся calls, затем results

            List<RunHistoryMessage> history = service.build(AGENT_ID, TRIGGER_ID).history();

            assertEquals(2, history.size());
            RunHistoryMessage calls = history.get(0);
            assertEquals("смотрю доску", calls.toolTurn().text());
            assertEquals("board.get_tasks", calls.toolTurn().calls().get(0).name());
            assertTrue(calls.toolTurn().results().isEmpty());
            RunHistoryMessage results = history.get(1);
            assertEquals("", results.text());
            assertTrue(results.toolTurn().calls().isEmpty());
            assertEquals("{\"tasks\":[]}", results.toolTurn().results().get(0).outputJson());
        }

        @Test
        @DisplayName("гигантский output tool_turn режется до контекстного бюджета")
        void toolTurnOutputCapped() {
            Agent agent = agent();
            AgentRun run = run(agent, triggerLog("time", "due"), null);
            run.setSessionId(SESSION_ID);
            stubRun(run);
            stubSkills(List.of());
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of());

            String huge = "x".repeat(RunContextService.TOOL_JSON_CONTEXT_CAP + 100);
            ChannelSessionMessage toolMsg = msg(ChannelSessionMessageKind.PROGRESS, "🔧 t", "TOOL_CALL");
            toolMsg.setMessageJson(Map.of(
                    "calls", List.of(Map.of("id", "c1", "name", "t", "argumentsJson", "{}")),
                    "results", List.of(Map.of("id", "c1", "name", "t", "outputJson", huge, "failed", false))));
            when(messageRepository.findBySessionIdAndCompletedTrueOrderByIdDesc(eq(SESSION_ID), any()))
                    .thenReturn(List.of(toolMsg));

            List<RunHistoryMessage> history = service.build(AGENT_ID, TRIGGER_ID).history();

            String output = history.get(0).toolTurn().results().get(0).outputJson();
            assertTrue(output.endsWith("…[truncated]"));
            assertTrue(output.length() < huge.length());
        }

        @Test
        @DisplayName("без сессии история пуста")
        void noSessionNoHistory() {
            stubRun(run(agent(), triggerLog("time", "due"), null));
            stubSkills(List.of());
            when(connectionRepository.findActiveBoundToAgent(AGENT_ID)).thenReturn(List.of());

            assertTrue(service.build(AGENT_ID, TRIGGER_ID).history().isEmpty());
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
}
