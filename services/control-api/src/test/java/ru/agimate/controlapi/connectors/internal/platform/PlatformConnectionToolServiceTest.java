package ru.agimate.controlapi.connectors.internal.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.abac.AgentConnectionPolicyService;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.JsonSchema;
import ru.agimate.controlapi.connectors.core.dto.IntegrationValidationResult;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.AgentConnectionPolicy;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.enums.ConnectionAuthStatus;
import ru.agimate.controlapi.database.enums.PolicyKind;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.service.channel.ChannelService;
import ru.agimate.controlapi.service.channel.ChannelHandlerInfo;
import ru.agimate.controlapi.service.channel.ChannelService.CreateChannelData;
import ru.agimate.controlapi.service.channel.ChannelService.UpdateChannelData;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;
import ru.agimate.controlapi.service.connection.ConnectionBindingService.AgentConnectionView;
import ru.agimate.controlapi.service.connection.ConnectionBindingService.ConnectionAgentView;
import ru.agimate.controlapi.service.connection.ConnectionService;
import ru.agimate.controlapi.service.tool.ToolDefinitionService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("PlatformConnectionToolService")
class PlatformConnectionToolServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SELF_AGENT_ID = UUID.randomUUID();
    private static final UUID OTHER_AGENT_ID = UUID.randomUUID();

    private final ConnectorRepository connectorRepository = mock(ConnectorRepository.class);
    private final ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
    private final AgentConnectionRepository agentConnectionRepository = mock(AgentConnectionRepository.class);
    private final ToolDefinitionService toolDefinitionService = mock(ToolDefinitionService.class);
    private final ConnectionService connectionService = mock(ConnectionService.class);
    private final ConnectionBindingService connectionBindingService = mock(ConnectionBindingService.class);
    private final AgentConnectionPolicyService policyService = mock(AgentConnectionPolicyService.class);
    private final ChannelService channelService = mock(ChannelService.class);

    private final PlatformConnectionToolService connectionTools = new PlatformConnectionToolService(
            connectorRepository, connectionRepository, agentConnectionRepository,
            mock(ConnectorRegistry.class), toolDefinitionService, connectionService, connectionBindingService,
            policyService, channelService);
    private final PlatformConnectorService handler = new PlatformConnectorService(
            mock(PlatformAgentToolService.class), connectionTools, mock(PlatformLlmToolService.class),
            mock(PlatformWorkspaceToolService.class), mock(PlatformObservabilityToolService.class));

    PlatformConnectionToolServiceTest() {
        ReflectionTestUtils.setField(connectionTools, "frontendBaseUrl", "https://app.test");
    }

    /** Инициатор вызова = SELF_AGENT_ID: операции над ним должны блокироваться. */
    private static ConnectorEnv selfEnv() {
        return new ConnectorEnv(null, USER_ID, SELF_AGENT_ID, null, null, null, Map.of(), null);
    }

    /** Активный binding, чья connection принадлежит USER_ID — как его видит requireOwnedBinding. */
    private AgentConnection ownedBinding(UUID agentId) {
        AgentConnection binding = AgentConnection.builder().id(UUID.randomUUID()).agentId(agentId)
                .connectionId(UUID.randomUUID()).build();
        when(agentConnectionRepository.findById(binding.getId())).thenReturn(Optional.of(binding));
        Connection connection = Connection.builder().id(binding.getConnectionId()).userId(USER_ID).build();
        when(connectionRepository.findByIdNotDeleted(binding.getConnectionId())).thenReturn(Optional.of(connection));
        return binding;
    }

    private Channel channel(UUID id, UUID agentId) {
        return Channel.builder().id(id).userId(USER_ID).agentId(agentId).name("Support")
                .channelHandler("telegram").connectorCode("telegram").connectionId(UUID.randomUUID())
                .config(Map.of()).build();
    }

    @Nested
    @DisplayName("guard «не сам себя»")
    class SelfGuard {

        @Test
        @DisplayName("bind_connection над собой — ConnectorException")
        void bindConnectionSelfRejected() {
            assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(), "bind_connection",
                    Map.of("agentId", SELF_AGENT_ID.toString(), "connectionId", UUID.randomUUID().toString())));
            verifyNoInteractions(connectionBindingService);
        }

        @Test
        @DisplayName("unbind_connection над собой — ConnectorException")
        void unbindConnectionSelfRejected() {
            assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(), "unbind_connection",
                    Map.of("agentId", SELF_AGENT_ID.toString(), "connectionId", UUID.randomUUID().toString())));
            verifyNoInteractions(connectionBindingService);
        }

        @Test
        @DisplayName("list_agent_connections над собой — допустимо (read-only листинг)")
        void listAgentConnectionsOnSelfIsAllowed() {
            when(connectionBindingService.listForAgent(USER_ID, SELF_AGENT_ID)).thenReturn(List.of());

            Map<?, ?> result = (Map<?, ?>) handler.executeTool(selfEnv(),
                    "list_agent_connections", Map.of("agentId", SELF_AGENT_ID.toString()));

            assertEquals(List.of(), result.get("connections"));
            verify(connectionBindingService).listForAgent(USER_ID, SELF_AGENT_ID);
        }
    }

    @Nested
    @DisplayName("create_connection (deep-link)")
    class CreateConnection {

        @Test
        @DisplayName("integration-коннектор → setup_required + setupUrl, без записи в БД")
        void integrationReturnsSetupLink() {
            Connector telegram = mock(Connector.class);
            when(telegram.isIntegration()).thenReturn(true);
            when(connectorRepository.findById("telegram")).thenReturn(Optional.of(telegram));

            Map<String, Object> result = handler.executeTool(selfEnv(), "create_connection",
                    Map.of("connectorCode", "telegram", "name", "My Bot"));

            assertEquals("setup_required", result.get("status"));
            assertEquals("telegram", result.get("connectorCode"));
            String url = (String) result.get("setupUrl");
            assertTrue(url.startsWith("https://app.test/connections/new?connector=telegram"));
            assertTrue(url.contains("name=My+Bot"));
        }

        @Test
        @DisplayName("не-integration коннектор → ConnectorException")
        void nonIntegrationRejected() {
            when(connectorRepository.findById("board")).thenReturn(Optional.empty());
            assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(),
                    "create_connection", Map.of("connectorCode", "board")));
        }
    }

    @Nested
    @DisplayName("update_connection")
    class UpdateConnection {

        @Test
        @DisplayName("id и параметры уходят в сервис, результат — ConnectionBrief")
        void mapsIdsAndPassesTheParams() {
            UUID connectionId = UUID.randomUUID();
            Connection connection = Connection.builder().id(connectionId).connectorCode("telegram")
                    .name("Renamed").enabled(false).subCode("bot")
                    .authStatus(ConnectionAuthStatus.AUTHORIZED).build();
            when(connectionService.update(connectionId, USER_ID, false, "Renamed")).thenReturn(connection);

            Map<String, Object> result = handler.executeTool(selfEnv(), "update_connection",
                    Map.of("connectionId", connectionId.toString(), "name", "Renamed", "enabled", false));

            assertEquals("Renamed", result.get("name"));
            assertEquals(false, result.get("enabled"));
            verify(connectionService).update(connectionId, USER_ID, false, "Renamed");
        }

        @Test
        @DisplayName("пустое имя → null, чтобы сервис оставил текущее (PATCH)")
        void blankNameIsPassedAsNullSoTheServiceKeepsIt() {
            UUID connectionId = UUID.randomUUID();
            Connection connection = Connection.builder().id(connectionId).connectorCode("telegram")
                    .name("Keep me").enabled(true).subCode("bot")
                    .authStatus(ConnectionAuthStatus.AUTHORIZED).build();
            when(connectionService.update(connectionId, USER_ID, null, null)).thenReturn(connection);

            handler.executeTool(selfEnv(), "update_connection",
                    Map.of("connectionId", connectionId.toString(), "name", ""));

            verify(connectionService).update(connectionId, USER_ID, null, null);
        }
    }

    @Nested
    @DisplayName("delete_connection")
    class DeleteConnection {

        @Test
        @DisplayName("id уходит в сервис")
        void callsTheService() {
            UUID connectionId = UUID.randomUUID();

            Map<String, Object> result = handler.executeTool(selfEnv(), "delete_connection",
                    Map.of("connectionId", connectionId.toString()));

            assertEquals(true, result.get("ok"));
            verify(connectionService).delete(connectionId, USER_ID);
        }
    }

    @Nested
    @DisplayName("test_connection")
    class TestConnection {

        @Test
        @DisplayName("валидация маппится в ConnectionTestResult")
        void validatesAndMapsTheResult() {
            UUID connectionId = UUID.randomUUID();
            when(connectionService.getOwnedConnection(connectionId, USER_ID))
                    .thenReturn(Connection.builder().id(connectionId).build());
            when(connectionService.validate(connectionId, USER_ID))
                    .thenReturn(IntegrationValidationResult.failure("token", "bad token"));

            Map<String, Object> result = handler.executeTool(selfEnv(), "test_connection",
                    Map.of("connectionId", connectionId.toString()));

            assertEquals(false, result.get("valid"));
            assertEquals("token", result.get("errorField"));
            assertEquals("bad token", result.get("errorMessage"));
            assertEquals(false, result.get("authorizationRequired"));
            verify(connectionService).getOwnedConnection(connectionId, USER_ID);
            verify(connectionService).validate(connectionId, USER_ID);
        }

        @Test
        @DisplayName("authorizationRequired=true проходит в результат")
        void mapsAuthorizationRequired() {
            UUID connectionId = UUID.randomUUID();
            when(connectionService.getOwnedConnection(connectionId, USER_ID))
                    .thenReturn(Connection.builder().id(connectionId).build());
            when(connectionService.validate(connectionId, USER_ID))
                    .thenReturn(IntegrationValidationResult.authorizationRequired("id", "name", Map.of()));

            Map<String, Object> result = handler.executeTool(selfEnv(), "test_connection",
                    Map.of("connectionId", connectionId.toString()));

            assertEquals(true, result.get("valid"));
            assertEquals(true, result.get("authorizationRequired"));
        }
    }

    @Nested
    @DisplayName("list_connection_tools")
    class ListConnectionTools {

        @Test
        @DisplayName("обнаруженные инструменты маппятся в ToolBrief")
        void mapsTheDiscoveredTools() {
            UUID connectionId = UUID.randomUUID();
            ConnectorToolSpec spec = new ConnectorToolSpec("send_message", null, "Send a message",
                    JsonSchema.scalar("string", "The text to send"), null, null, null, null);
            when(toolDefinitionService.getConnectionTools(USER_ID, connectionId))
                    .thenReturn(Map.of("send_message", spec));

            Map<String, Object> result = handler.executeTool(selfEnv(), "list_connection_tools",
                    Map.of("connectionId", connectionId.toString()));

            List<?> tools = (List<?>) result.get("tools");
            assertEquals(1, tools.size());
            assertEquals("send_message", ((Map<?, ?>) tools.getFirst()).get("name"));
            assertEquals("Send a message", ((Map<?, ?>) tools.getFirst()).get("description"));
            // The input schema rides along — it is what a params_filter is written against.
            Map<?, ?> schema = (Map<?, ?>) ((Map<?, ?>) tools.getFirst()).get("inputSchema");
            assertEquals("string", schema.get("type"));
            assertEquals("The text to send", schema.get("description"));
            verify(toolDefinitionService).getConnectionTools(USER_ID, connectionId);
        }
    }

    @Nested
    @DisplayName("list_connection_agents")
    class ListConnectionAgents {

        @Test
        @DisplayName("views маппятся в AgentBinding (включая disabled-агентов)")
        void mapsTheViews() {
            UUID connectionId = UUID.randomUUID();
            Agent agent = Agent.builder().id(OTHER_AGENT_ID).userId(USER_ID).name("Worker")
                    .enabled(false).build();
            AgentConnection binding = AgentConnection.builder().id(UUID.randomUUID())
                    .agentId(OTHER_AGENT_ID).connectionId(connectionId).build();
            when(connectionBindingService.listForConnection(USER_ID, connectionId))
                    .thenReturn(List.of(new ConnectionAgentView(binding, agent)));

            Map<String, Object> result = handler.executeTool(selfEnv(), "list_connection_agents",
                    Map.of("connectionId", connectionId.toString()));

            List<?> agents = (List<?>) result.get("agents");
            assertEquals(1, agents.size());
            Map<?, ?> first = (Map<?, ?>) agents.getFirst();
            assertEquals(OTHER_AGENT_ID.toString(), first.get("agentId"));
            assertEquals("Worker", first.get("agentName"));
            assertEquals(false, first.get("enabled"));
            verify(connectionBindingService).listForConnection(USER_ID, connectionId);
        }
    }

    @Nested
    @DisplayName("list_agent_connections")
    class ListAgentConnections {

        @Test
        @DisplayName("views маппятся в AgentConnectionItem")
        void mapsTheViews() {
            UUID connectionId = UUID.randomUUID();
            Connection connection = Connection.builder().id(connectionId).connectorCode("telegram")
                    .name("Main").enabled(true).authStatus(ConnectionAuthStatus.PENDING_AUTH).build();
            AgentConnection binding = AgentConnection.builder().id(UUID.randomUUID())
                    .agentId(OTHER_AGENT_ID).connectionId(connectionId).build();
            when(connectionBindingService.listForAgent(USER_ID, OTHER_AGENT_ID))
                    .thenReturn(List.of(new AgentConnectionView(binding, connection, false)));

            Map<String, Object> result = handler.executeTool(selfEnv(), "list_agent_connections",
                    Map.of("agentId", OTHER_AGENT_ID.toString()));

            List<?> items = (List<?>) result.get("connections");
            assertEquals(1, items.size());
            Map<?, ?> first = (Map<?, ?>) items.getFirst();
            assertEquals(connectionId.toString(), first.get("connectionId"));
            assertEquals("telegram", first.get("connectorCode"));
            assertEquals("PENDING_AUTH", first.get("authStatus"));
            assertEquals(false, first.get("managedBySkills"));
            verify(connectionBindingService).listForAgent(USER_ID, OTHER_AGENT_ID);
        }
    }

    @Nested
    @DisplayName("channels")
    class Channels {

        @Test
        @DisplayName("list_channels без фильтра — все каналы пользователя")
        void listChannelsWithoutFilter() {
            Channel channel = channel(UUID.randomUUID(), OTHER_AGENT_ID);
            when(channelService.listForUser(USER_ID)).thenReturn(List.of(channel));

            Map<String, Object> result = handler.executeTool(selfEnv(), "list_channels", Map.of());

            List<?> channels = (List<?>) result.get("channels");
            assertEquals(1, channels.size());
            assertEquals("Support", ((Map<?, ?>) channels.getFirst()).get("name"));
            verify(channelService).listForUser(USER_ID);
        }

        @Test
        @DisplayName("list_channels с фильтром по агенту — agentId это фильтр, без self-guard")
        void listChannelsByAgentFilter() {
            handler.executeTool(selfEnv(), "list_channels",
                    Map.of("agentId", SELF_AGENT_ID.toString()));
            verify(channelService).listForUserAndAgent(USER_ID, SELF_AGENT_ID);
        }

        @Test
        @DisplayName("get_channel возвращает config и inputFilter")
        void getChannelReturnsDetail() {
            UUID channelId = UUID.randomUUID();
            Channel channel = channel(channelId, OTHER_AGENT_ID);
            channel.setConfig(Map.of("tokenField", "x"));
            channel.setInputFilter(Map.of("chatId", "123"));
            when(channelService.getById(USER_ID, channelId)).thenReturn(channel);

            Map<String, Object> result = handler.executeTool(selfEnv(), "get_channel",
                    Map.of("id", channelId.toString()));

            assertEquals("Support", result.get("name"));
            assertEquals(Map.of("tokenField", "x"), result.get("config"));
            assertEquals(Map.of("chatId", "123"), result.get("inputFilter"));
            verify(channelService).getById(USER_ID, channelId);
        }

        @Test
        @DisplayName("create_channel на не-push агента — ошибка сервиса переведена, код коннектора из connection")
        void nonPushAgentIsTranslated() {
            UUID connectionId = UUID.randomUUID();
            // The connector code is derived from the connection, owner-scoped, not guessed by the model.
            when(connectionRepository.findByIdAndUserIdNotDeleted(connectionId, USER_ID)).thenReturn(Optional.of(
                    Connection.builder().id(connectionId).connectorCode("telegram").build()));
            when(channelService.create(eq(USER_ID), any(CreateChannelData.class))).thenThrow(
                    new BadRequestStatusException("Agent of type WEBHOOK receives no messages, "
                            + "so it cannot have channels"));

            var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(),
                    "create_channel", Map.of("agentId", OTHER_AGENT_ID.toString(), "name", "Support",
                            "channelHandler", "telegram",
                            "connectionId", connectionId.toString())));
            assertTrue(ex.getMessage().contains("receives no messages"));
            ArgumentCaptor<CreateChannelData> captor = ArgumentCaptor.forClass(CreateChannelData.class);
            verify(channelService).create(eq(USER_ID), captor.capture());
            assertEquals(OTHER_AGENT_ID, captor.getValue().agentId());
            assertEquals("telegram", captor.getValue().connectorCode());
        }

        @Test
        @DisplayName("create_channel: чужая connection — «Connection not found», сервис не вызывается")
        void foreignConnectionIsRejected() {
            UUID connectionId = UUID.randomUUID();
            when(connectionRepository.findByIdAndUserIdNotDeleted(connectionId, USER_ID))
                    .thenReturn(Optional.empty());

            var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(),
                    "create_channel", Map.of("agentId", OTHER_AGENT_ID.toString(), "name", "Support",
                            "channelHandler", "telegram", "connectionId", connectionId.toString())));
            assertTrue(ex.getMessage().contains("Connection not found"));
            verify(channelService, never()).create(any(), any());
        }

        @Test
        @DisplayName("create_channel над собой — ConnectorException")
        void createChannelSelfRejected() {
            assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(), "create_channel",
                    Map.of("agentId", SELF_AGENT_ID.toString(), "name", "Support",
                            "channelHandler", "telegram",
                            "connectionId", UUID.randomUUID().toString())));
            verifyNoInteractions(channelService);
        }

        @Test
        @DisplayName("update_channel: пустой inputFilter → clearInputFilter=true (captor)")
        void emptyInputFilterSetsTheClearFlag() {
            UUID channelId = UUID.randomUUID();
            Channel existing = channel(channelId, OTHER_AGENT_ID);
            when(channelService.getById(USER_ID, channelId)).thenReturn(existing);
            when(channelService.update(eq(USER_ID), eq(channelId), any(UpdateChannelData.class)))
                    .thenReturn(existing);

            handler.executeTool(selfEnv(), "update_channel",
                    Map.of("id", channelId.toString(), "inputFilter", Map.of()));

            ArgumentCaptor<UpdateChannelData> captor = ArgumentCaptor.forClass(UpdateChannelData.class);
            verify(channelService).update(eq(USER_ID), eq(channelId), captor.capture());
            UpdateChannelData data = captor.getValue();
            assertTrue(data.clearInputFilter());
            assertNotNull(data.inputFilter());
            assertTrue(data.inputFilter().isEmpty());
            assertNull(data.name());
            assertNull(data.config());
        }

        @Test
        @DisplayName("update_channel: пустое имя — ConnectorException (имя нельзя очистить)")
        void blankNameRejected() {
            UUID channelId = UUID.randomUUID();
            Channel channel = Channel.builder().id(channelId).agentId(OTHER_AGENT_ID).build();
            when(channelService.getById(USER_ID, channelId)).thenReturn(channel);

            var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(),
                    "update_channel", Map.of("id", channelId.toString(), "name", "")));
            assertTrue(ex.getMessage().contains("blank"));
            verify(channelService, never()).update(any(), any(), any());
        }

        @Test
        @DisplayName("update_channel: отсутствующий inputFilter → keep (null + флаг false)")
        void nullInputFilterKeepsIt() {
            UUID channelId = UUID.randomUUID();
            Channel existing = channel(channelId, OTHER_AGENT_ID);
            when(channelService.getById(USER_ID, channelId)).thenReturn(existing);
            when(channelService.update(eq(USER_ID), eq(channelId), any(UpdateChannelData.class)))
                    .thenReturn(existing);

            handler.executeTool(selfEnv(), "update_channel",
                    Map.of("id", channelId.toString(), "name", "Renamed"));

            ArgumentCaptor<UpdateChannelData> captor = ArgumentCaptor.forClass(UpdateChannelData.class);
            verify(channelService).update(eq(USER_ID), eq(channelId), captor.capture());
            UpdateChannelData data = captor.getValue();
            assertFalse(data.clearInputFilter());
            assertNull(data.inputFilter());
            assertEquals("Renamed", data.name());
        }

        @Test
        @DisplayName("update_channel: канал с subject-агентом = вызывающий — ConnectorException")
        void updateChannelOnOwnSubjectRejected() {
            UUID channelId = UUID.randomUUID();
            when(channelService.getById(USER_ID, channelId)).thenReturn(channel(channelId, SELF_AGENT_ID));

            assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(), "update_channel",
                    Map.of("id", channelId.toString(), "name", "X")));
            verify(channelService, never()).update(any(), any(), any());
        }

        @Test
        @DisplayName("delete_channel удаляет канал другого агента")
        void deleteChannelWorks() {
            UUID channelId = UUID.randomUUID();
            when(channelService.getById(USER_ID, channelId)).thenReturn(channel(channelId, OTHER_AGENT_ID));

            Map<String, Object> result = handler.executeTool(selfEnv(), "delete_channel",
                    Map.of("id", channelId.toString()));

            assertEquals(true, result.get("ok"));
            verify(channelService).delete(USER_ID, channelId);
        }

        @Test
        @DisplayName("delete_channel: канал с subject-агентом = вызывающий — ConnectorException")
        void deleteChannelOnOwnSubjectRejected() {
            UUID channelId = UUID.randomUUID();
            when(channelService.getById(USER_ID, channelId)).thenReturn(channel(channelId, SELF_AGENT_ID));

            assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(), "delete_channel",
                    Map.of("id", channelId.toString())));
            verify(channelService, never()).delete(any(), any());
        }

        @Test
        @DisplayName("list_channel_handlers возвращает плоский список обработчиков")
        void listChannelHandlersReturnsTheFlatList() {
            when(channelService.listHandlersFlat()).thenReturn(List.of(
                    new ChannelHandlerInfo("telegram", Map.of("type", "object")),
                    new ChannelHandlerInfo("webchat", Map.of("type", "object"))));

            Map<String, Object> result = handler.executeTool(selfEnv(), "list_channel_handlers", Map.of());

            List<?> handlers = (List<?>) result.get("handlers");
            assertEquals(2, handlers.size());
            assertEquals("telegram", ((Map<?, ?>) handlers.getFirst()).get("name"));
            verify(channelService).listHandlersFlat();
        }
    }

    @Nested
    @DisplayName("ABAC policies")
    class Policies {

        @Test
        @DisplayName("list_policies маппит сущности (read-only, без self-guard по subject)")
        void listMapsThePolicies() {
            AgentConnection binding = ownedBinding(OTHER_AGENT_ID);
            AgentConnectionPolicy policy = AgentConnectionPolicy.builder().id(UUID.randomUUID())
                    .agentConnectionId(binding.getId()).kind(PolicyKind.TRIGGER).name(null)
                    .effect(AccessEffect.DENY).paramsFilter(Map.of("chatId", "1"))
                    .description("binding-wide deny").build();
            when(policyService.getPolicies(USER_ID, binding.getId())).thenReturn(List.of(policy));

            Map<String, Object> result = handler.executeTool(selfEnv(), "list_policies",
                    Map.of("agentConnectionId", binding.getId().toString()));

            List<?> policies = (List<?>) result.get("policies");
            assertEquals(1, policies.size());
            Map<?, ?> first = (Map<?, ?>) policies.getFirst();
            assertEquals("TRIGGER", first.get("kind"));
            assertNull(first.get("name"));
            assertEquals("DENY", first.get("effect"));
            verify(policyService).getPolicies(USER_ID, binding.getId());
        }

        @Test
        @DisplayName("create_policy с мусорным kind — ConnectorException со списком TOOL/TRIGGER")
        void garbageKindListsAllowedValues() {
            AgentConnection binding = ownedBinding(OTHER_AGENT_ID);

            var ex = assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(),
                    "create_policy", Map.of("agentConnectionId", binding.getId().toString(),
                            "kind", "BOGUS", "effect", "ALLOW")));

            assertTrue(ex.getMessage().contains("TOOL"));
            assertTrue(ex.getMessage().contains("TRIGGER"));
            verifyNoInteractions(policyService);
        }

        @Test
        @DisplayName("create_policy маппит параметры и энумы")
        void createMapsParamsAndEnums() {
            AgentConnection binding = ownedBinding(OTHER_AGENT_ID);
            AgentConnectionPolicy created = AgentConnectionPolicy.builder().id(UUID.randomUUID())
                    .agentConnectionId(binding.getId()).kind(PolicyKind.TOOL).name("read")
                    .effect(AccessEffect.ALLOW).paramsFilter(Map.of()).description("d").build();
            when(policyService.create(eq(USER_ID), eq(binding.getId()), eq(PolicyKind.TOOL), eq("read"),
                    eq(AccessEffect.ALLOW), any(), eq("d"))).thenReturn(created);

            Map<String, Object> result = handler.executeTool(selfEnv(), "create_policy",
                    Map.of("agentConnectionId", binding.getId().toString(), "kind", "TOOL",
                            "name", "read", "effect", "ALLOW", "paramsFilter", Map.of(), "description", "d"));

            assertEquals("TOOL", result.get("kind"));
            assertEquals("ALLOW", result.get("effect"));
            verify(policyService).create(eq(USER_ID), eq(binding.getId()), eq(PolicyKind.TOOL), eq("read"),
                    eq(AccessEffect.ALLOW), any(), eq("d"));
        }

        @Test
        @DisplayName("create_policy: subject binding'а = вызывающий агент — ConnectorException")
        void createOnOwnBindingRejected() {
            AgentConnection binding = ownedBinding(SELF_AGENT_ID);

            assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(), "create_policy",
                    Map.of("agentConnectionId", binding.getId().toString(), "kind", "TOOL",
                            "effect", "ALLOW")));
            verifyNoInteractions(policyService);
        }

        @Test
        @DisplayName("update_policy: отсутствующий paramsFilter сохраняет текущий (captor)")
        void updateAbsentParamsFilterKeepsTheCurrentOne() {
            AgentConnection binding = ownedBinding(OTHER_AGENT_ID);
            UUID policyId = UUID.randomUUID();
            AgentConnectionPolicy current = AgentConnectionPolicy.builder().id(policyId)
                    .agentConnectionId(binding.getId()).kind(PolicyKind.TOOL).name("read")
                    .effect(AccessEffect.DENY).paramsFilter(Map.of("key", "value")).description("old").build();
            when(policyService.getPolicyById(USER_ID, policyId)).thenReturn(current);
            when(policyService.update(any(), any(), any(), any(), any(), any())).thenReturn(current);

            handler.executeTool(selfEnv(), "update_policy",
                    Map.of("agentConnectionId", binding.getId().toString(),
                            "policyId", policyId.toString()));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> filterCaptor = ArgumentCaptor.forClass(Map.class);
            // Description passes through raw: absent = null, and the service resolves null=keep.
            verify(policyService).update(eq(USER_ID), eq(binding.getId()), eq(policyId), eq(AccessEffect.DENY),
                    filterCaptor.capture(), isNull());
            assertEquals(Map.of("key", "value"), filterCaptor.getValue());
        }

        @Test
        @DisplayName("update_policy: пустая строка description очищает (→ null, канон /manage)")
        void updateBlankDescriptionClears() {
            AgentConnection binding = ownedBinding(OTHER_AGENT_ID);
            UUID policyId = UUID.randomUUID();
            AgentConnectionPolicy current = AgentConnectionPolicy.builder().id(policyId)
                    .agentConnectionId(binding.getId()).kind(PolicyKind.TOOL).name("read")
                    .effect(AccessEffect.DENY).paramsFilter(null).description("old").build();
            when(policyService.getPolicyById(USER_ID, policyId)).thenReturn(current);
            when(policyService.update(any(), any(), any(), any(), any(), any())).thenReturn(current);

            handler.executeTool(selfEnv(), "update_policy",
                    Map.of("agentConnectionId", binding.getId().toString(),
                            "policyId", policyId.toString(), "description", ""));

            // The tool passes "" through raw — the service (not the tool) resolves "" → null (clear).
            verify(policyService).update(eq(USER_ID), eq(binding.getId()), eq(policyId), eq(AccessEffect.DENY),
                    isNull(), eq(""));
        }

        @Test
        @DisplayName("update_policy: пустой paramsFilter очищает (→ null)")
        void updateEmptyParamsFilterClears() {
            AgentConnection binding = ownedBinding(OTHER_AGENT_ID);
            UUID policyId = UUID.randomUUID();
            AgentConnectionPolicy current = AgentConnectionPolicy.builder().id(policyId)
                    .agentConnectionId(binding.getId()).kind(PolicyKind.TOOL).name("read")
                    .effect(AccessEffect.DENY).paramsFilter(Map.of("key", "value")).description("old").build();
            when(policyService.getPolicyById(USER_ID, policyId)).thenReturn(current);
            when(policyService.update(any(), any(), any(), any(), any(), any())).thenReturn(current);

            handler.executeTool(selfEnv(), "update_policy",
                    Map.of("agentConnectionId", binding.getId().toString(),
                            "policyId", policyId.toString(), "paramsFilter", Map.of()));

            // Description absent → null: the service resolves null=keep.
            verify(policyService).update(eq(USER_ID), eq(binding.getId()), eq(policyId), eq(AccessEffect.DENY),
                    isNull(), isNull());
        }

        @Test
        @DisplayName("update_policy: subject binding'а = вызывающий агент — ConnectorException")
        void updateOnOwnBindingRejected() {
            AgentConnection binding = ownedBinding(SELF_AGENT_ID);

            assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(), "update_policy",
                    Map.of("agentConnectionId", binding.getId().toString(),
                            "policyId", UUID.randomUUID().toString())));
            verifyNoInteractions(policyService);
        }

        @Test
        @DisplayName("delete_policy удаляет и возвращает ok")
        void deleteCallsTheService() {
            AgentConnection binding = ownedBinding(OTHER_AGENT_ID);
            UUID policyId = UUID.randomUUID();

            Map<String, Object> result = handler.executeTool(selfEnv(), "delete_policy",
                    Map.of("agentConnectionId", binding.getId().toString(),
                            "policyId", policyId.toString()));

            assertEquals(true, result.get("ok"));
            verify(policyService).delete(USER_ID, binding.getId(), policyId);
        }

        @Test
        @DisplayName("delete_policy: subject binding'а = вызывающий агент — ConnectorException")
        void deleteOnOwnBindingRejected() {
            AgentConnection binding = ownedBinding(SELF_AGENT_ID);

            assertThrows(ConnectorException.class, () -> handler.executeTool(selfEnv(), "delete_policy",
                    Map.of("agentConnectionId", binding.getId().toString(),
                            "policyId", UUID.randomUUID().toString())));
            verifyNoInteractions(policyService);
        }
    }
}
