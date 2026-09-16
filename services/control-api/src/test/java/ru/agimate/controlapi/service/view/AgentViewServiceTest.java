package ru.agimate.controlapi.service.view;

import ru.agimate.controlapi.connectors.core.dto.ToolAudience;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.agimate.common.rest.error.CustomResponseStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.TooManyRequestsStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorEnvFactory;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.ViewProvider;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.ToolUi;
import ru.agimate.controlapi.connectors.core.dto.ViewCallResult;
import ru.agimate.controlapi.connectors.core.dto.ViewPage;
import ru.agimate.controlapi.controller.manage.dto.AgentViewContentResponse;
import ru.agimate.controlapi.controller.manage.dto.AgentViewResponse;
import ru.agimate.controlapi.controller.manage.dto.ViewToolCallRequest;
import ru.agimate.controlapi.controller.manage.dto.ViewToolCallResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.enums.ToolCallInitiator;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.service.AgentService;
import ru.agimate.controlapi.service.dto.ToolResult;
import ru.agimate.controlapi.service.mcp.McpToolCatalog;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter;
import ru.agimate.controlapi.service.tool.AgentToolCallService;
import ru.agimate.controlapi.service.tool.AgentToolCallService.CallOutcome;

import java.util.LinkedHashMap;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentViewService — вью коннекторов агента")
class AgentViewServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final String VIEW = "ui://server-everything/weather-dashboard";

    @Mock
    private AgentService agentService;
    @Mock
    private McpToolCatalog toolCatalog;
    @Mock
    private ConnectionRepository connectionRepository;
    @Mock
    private ConnectorEnvFactory connectorEnvFactory;
    @Mock
    private ConnectorRegistry connectorRegistry;
    @Mock
    private AgentToolCallService agentToolCallService;
    @Mock
    private InboundRateLimiter rateLimiter;

    @InjectMocks
    private AgentViewService service;

    private final Map<String, McpToolCatalog.ToolEntry> catalog = new LinkedHashMap<>();
    private final ViewProvider viewProvider = mock(ViewProvider.class);

    @BeforeEach
    void setUp() {
        Agent agent = Agent.builder().id(AGENT_ID).userId(USER_ID).build();
        when(agentService.findById(AGENT_ID)).thenReturn(agent);
        when(toolCatalog.forAgent(agent, ToolAudience.ALL)).thenReturn(catalog);
        when(rateLimiter.tryAcquire(any(), any())).thenReturn(true);
        Connection connection = Connection.builder().id(CONNECTION_ID).name("Everything").build();
        when(connectionRepository.findByIdNotDeleted(CONNECTION_ID)).thenReturn(Optional.of(connection));
        when(connectionRepository.findByIdInNotDeleted(any())).thenReturn(List.of(connection));
        when(connectorRegistry.findCapability("mcp", ViewProvider.class)).thenReturn(Optional.of(viewProvider));
        when(connectorRegistry.findCapability("sheets", ViewProvider.class)).thenReturn(Optional.empty());
    }

    private void tool(UUID connectionId, String connectorCode, String name, ToolUi ui) {
        ConnectorToolSpec spec = new ConnectorToolSpec(name, null, null, null, null, null, null, null, null, ui);
        catalog.put(connectorCode + "__" + name + connectionId,
                new McpToolCatalog.ToolEntry(connectionId, connectorCode, name, spec));
    }

    @Test
    @DisplayName("чужой агент неотличим от несуществующего: 404")
    void foreignAgent() {
        assertThrows(NotFoundStatusException.class, () -> service.list(AGENT_ID, UUID.randomUUID()));
    }

    @Nested
    @DisplayName("список")
    class ListViews {

        @Test
        @DisplayName("тулы одной вью группируются; тулы без вью и коннекторы без вью не попадают")
        void groupsByConnectionAndUri() {
            tool(CONNECTION_ID, "mcp", "show-weather-dashboard", new ToolUi(VIEW, List.of("model", "app")));
            tool(CONNECTION_ID, "mcp", "refresh-weather", new ToolUi(VIEW, List.of("app")));
            tool(CONNECTION_ID, "mcp", "helper", new ToolUi(null, List.of("app")));
            tool(CONNECTION_ID, "mcp", "echo", null);
            tool(UUID.randomUUID(), "sheets", "read", new ToolUi("ui://sheets/table", null));

            List<AgentViewResponse> views = service.list(AGENT_ID, USER_ID);

            assertEquals(1, views.size());
            assertEquals(VIEW, views.getFirst().uri());
            assertEquals("Everything", views.getFirst().connectionName());
            assertEquals(List.of("show-weather-dashboard", "refresh-weather"), views.getFirst().tools());
        }
    }

    @Nested
    @DisplayName("содержимое")
    class Content {

        @Test
        @DisplayName("uri, на который не ссылается тул этой коннекции, не читается")
        void undeclaredUriIsNotProxied() {
            tool(CONNECTION_ID, "mcp", "show", new ToolUi(VIEW, null));

            assertThrows(NotFoundStatusException.class,
                    () -> service.content(AGENT_ID, USER_ID, CONNECTION_ID, "ui://server-everything/other"));
            assertThrows(NotFoundStatusException.class,
                    () -> service.content(AGENT_ID, USER_ID, UUID.randomUUID(), VIEW));
            verify(viewProvider, never()).readView(any(), any());
        }

        @Test
        @DisplayName("страница коннектора отдаётся как есть")
        void returnsPage() {
            tool(CONNECTION_ID, "mcp", "show", new ToolUi(VIEW, null));
            when(viewProvider.readView(any(), eq(VIEW)))
                    .thenReturn(new ViewPage("text/html;profile=mcp-app", "<html></html>", null, true));

            AgentViewContentResponse page = service.content(AGENT_ID, USER_ID, CONNECTION_ID, VIEW);

            assertEquals("<html></html>", page.html());
            assertTrue(page.prefersBorder());
            assertNull(page.permissions());
        }

        @Test
        @DisplayName("коннектор не отдал страницу → 502")
        void badGateway() {
            tool(CONNECTION_ID, "mcp", "show", new ToolUi(VIEW, null));
            when(viewProvider.readView(any(), eq(VIEW))).thenThrow(new ConnectorException("down"));

            assertEquals(502, assertThrows(CustomResponseStatusException.class,
                    () -> service.content(AGENT_ID, USER_ID, CONNECTION_ID, VIEW)).getHttpCode());
        }
    }

    @Nested
    @DisplayName("вызов тула")
    class Call {

        private final ViewToolCallRequest request =
                new ViewToolCallRequest(CONNECTION_ID, "show", Map.of("location", "Tokyo"));

        @Test
        @DisplayName("тула нет в каталоге агента (привязки, ABAC) → 404")
        void notInCatalog() {
            tool(UUID.randomUUID(), "mcp", "show", new ToolUi(VIEW, List.of("app")));

            assertThrows(NotFoundStatusException.class, () -> service.call(AGENT_ID, USER_ID, request));
        }

        @Test
        @DisplayName("тул, объявленный только для модели → 403, вызов не создаётся")
        void notCallableFromView() {
            tool(CONNECTION_ID, "mcp", "show", new ToolUi(VIEW, List.of("model")));

            assertThrows(ForbiddenStatusException.class, () -> service.call(AGENT_ID, USER_ID, request));
            verify(agentToolCallService, never()).callAsAgent(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("по спеке: тул без объявленной видимости вью вызвать может")
        void undeclaredVisibilityIsCallable() {
            tool(CONNECTION_ID, "mcp", "show", null);
            tool(CONNECTION_ID, "mcp", "dashboard", new ToolUi(VIEW, null));
            completes(new ToolResult("x", "mcp", "{}", null));

            assertFalse(service.call(AGENT_ID, USER_ID, request).isError());
        }

        @Test
        @DisplayName("коннекция без вью: её тулы из вью не зовутся, даже без объявленной видимости")
        void connectionWithoutViews() {
            tool(CONNECTION_ID, "mcp", "show", null);

            assertThrows(NotFoundStatusException.class, () -> service.call(AGENT_ID, USER_ID, request));
            verify(agentToolCallService, never()).callAsAgent(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("коннектор без вью: тулы не зовутся")
        void connectorWithoutViews() {
            tool(CONNECTION_ID, "sheets", "show", new ToolUi("ui://sheets/table", null));

            assertThrows(NotFoundStatusException.class, () -> service.call(AGENT_ID, USER_ID, request));
        }

        @Test
        @DisplayName("лимит частоты → 429 до обращения к каталогу")
        void rateLimited() {
            when(rateLimiter.tryAcquire(InboundRateLimiter.Scope.VIEW_CALL, AGENT_ID)).thenReturn(false);

            assertThrows(TooManyRequestsStatusException.class, () -> service.call(AGENT_ID, USER_ID, request));
            verify(toolCatalog, never()).forAgent(any(), any());
        }

        @Test
        @DisplayName("отказ правил на вызове → isError, а не транспортная ошибка")
        void policyRefusal() {
            tool(CONNECTION_ID, "mcp", "show", new ToolUi(VIEW, List.of("app")));
            when(agentToolCallService.callAsAgent(eq(AGENT_ID), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new CallOutcome.Refused("denied"));

            ViewToolCallResponse response = service.call(AGENT_ID, USER_ID, request);

            assertTrue(response.isError());
            assertEquals(List.of(Map.of("type", "text", "text", "denied")), response.content());
        }

        @Test
        @DisplayName("вызов от имени агента с инициатором VIEW; результат разбирает коннектор вью")
        void completedCall() {
            tool(CONNECTION_ID, "mcp", "show", new ToolUi(VIEW, List.of("app")));
            ToolResult result = new ToolResult("x", "mcp", "raw", null);
            when(agentToolCallService.callAsAgent(AGENT_ID, "mcp", CONNECTION_ID, "show",
                    Map.of("location", "Tokyo"), java.time.Duration.ofSeconds(30), ToolCallInitiator.VIEW))
                    .thenReturn(new CallOutcome.Completed(result));
            when(viewProvider.toViewResult("raw")).thenReturn(new ViewCallResult(List.of(), Map.of("temp", 12), false));

            ViewToolCallResponse response = service.call(AGENT_ID, USER_ID, request);

            assertFalse(response.isError());
            assertEquals(Map.of("temp", 12), response.structuredContent());
        }

        @Test
        @DisplayName("ошибка коннектора из лога → isError с текстом, мимо разбора коннектором")
        void connectorError() {
            tool(CONNECTION_ID, "mcp", "show", new ToolUi(VIEW, List.of("app")));
            completes(new ToolResult("x", "mcp", null, "boom"));

            ViewToolCallResponse response = service.call(AGENT_ID, USER_ID, request);

            assertTrue(response.isError());
            assertEquals(List.of(Map.of("type", "text", "text", "boom")), response.content());
            verify(viewProvider, never()).toViewResult(any());
        }

        @Test
        @DisplayName("таймаут → isError, исполнение дописывает лог само")
        void timeout() {
            tool(CONNECTION_ID, "mcp", "show", new ToolUi(VIEW, List.of("app")));
            when(agentToolCallService.callAsAgent(eq(AGENT_ID), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new CallOutcome.StillRunning(null));

            assertTrue(service.call(AGENT_ID, USER_ID, request).isError());
        }

        private void completes(ToolResult result) {
            when(agentToolCallService.callAsAgent(eq(AGENT_ID), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new CallOutcome.Completed(result));
            when(viewProvider.toViewResult(any())).thenReturn(ViewCallResult.text("ok"));
        }
    }
}
