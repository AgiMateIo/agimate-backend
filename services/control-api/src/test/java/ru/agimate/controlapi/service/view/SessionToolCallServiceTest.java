package ru.agimate.controlapi.service.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.ViewProvider;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.ToolUi;
import ru.agimate.controlapi.connectors.core.dto.ViewCallResult;
import ru.agimate.controlapi.controller.manage.dto.session.SessionToolCallResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.service.mcp.McpToolCatalog;
import ru.agimate.controlapi.service.session.AgentSessionService;
import ru.agimate.controlapi.service.tool.ToolCallLogService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionToolCallService")
class SessionToolCallServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final String VIEW_URI = "ui://persist-memory/memory";

    @Mock private AgentSessionService agentSessionService;
    @Mock private AgentViewService agentViewService;
    @Mock private ToolCallLogService toolCallLogService;
    @Mock private ViewProvider viewProvider;

    private SessionToolCallService service;
    private Agent agent;
    private AgentSession session;

    @BeforeEach
    void setUp() {
        service = new SessionToolCallService(agentSessionService, agentViewService, toolCallLogService);
        agent = Agent.builder().id(AGENT_ID).userId(USER_ID).build();
        session = AgentSession.builder().id(SESSION_ID).userId(USER_ID).agentId(AGENT_ID).build();
        when(agentSessionService.getById(SESSION_ID)).thenReturn(session);
    }

    private ToolCallLog call() {
        return ToolCallLog.builder()
                .externalId("call-1")
                .name("get_memory")
                .connectionId(CONNECTION_ID.toString())
                .agentSessionId(SESSION_ID.toString())
                .input(Map.of("q", "x"))
                .build();
    }

    private McpToolCatalog.ToolEntry entry(ToolUi ui) {
        return new McpToolCatalog.ToolEntry(CONNECTION_ID, "persist-memory", "get_memory",
                new ConnectorToolSpec("get_memory", null, "Get memory", null, null, null, null, null, null, ui));
    }

    private void stubView(ToolUi ui, ToolCallLog call) {
        when(agentViewService.ownedAgent(AGENT_ID, USER_ID)).thenReturn(agent);
        when(agentViewService.viewCatalog(agent)).thenReturn(List.of(entry(ui)));
        when(toolCallLogService.findByExternalIdAndAgentId("call-1", AGENT_ID))
                .thenReturn(Optional.of(call));
    }

    private static ToolUi view() {
        return new ToolUi(VIEW_URI, List.of("model", "app"));
    }

    @Test
    @DisplayName("вызов резолвится во вью: uri, аргументы и CallToolResult из журнала")
    void resolvesView() {
        ToolCallLog call = call();
        call.setFinishAt(LocalDateTime.now());
        call.setOutput("{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}");
        stubView(view(), call);
        when(agentViewService.viewProvider(entry(view()))).thenReturn(viewProvider);
        when(viewProvider.toViewResult(call.getOutput()))
                .thenReturn(new ViewCallResult(List.of(Map.of("type", "text", "text", "hi")), Map.of("cold", 1), false));

        SessionToolCallResponse response = service.get(USER_ID, SESSION_ID, "call-1");

        assertEquals(VIEW_URI, response.viewUri());
        assertEquals(Map.of("q", "x"), response.input());
        assertEquals(Map.of("cold", 1), response.result().structuredContent());
        assertEquals("hi", ((Map<?, ?>) response.result().content().get(0)).get("text"));
    }

    @Test
    @DisplayName("вызов ещё идёт: вью и аргументы есть, результата нет")
    void runningCallHasNoResult() {
        ToolCallLog call = call();
        stubView(view(), call);

        SessionToolCallResponse response = service.get(USER_ID, SESSION_ID, "call-1");

        assertEquals(VIEW_URI, response.viewUri());
        assertEquals(Map.of("q", "x"), response.input());
        assertNull(response.result());
    }

    @Test
    @DisplayName("отказ в исполнении — CallToolResult с isError, даже если вызов так и не завершился")
    void deniedCallIsAnErrorResult() {
        ToolCallLog call = call();
        call.setError("Tool arguments rejected by params_filter");
        stubView(view(), call);

        SessionToolCallResponse response = service.get(USER_ID, SESSION_ID, "call-1");

        assertTrue(response.result().isError());
        assertEquals("Tool arguments rejected by params_filter",
                ((Map<?, ?>) response.result().content().get(0)).get("text"));
    }

    @Test
    @DisplayName("у тула нет ui:// — отдаётся только идентичность, без данных")
    void toolWithoutView() {
        ToolCallLog call = call();
        call.setFinishAt(LocalDateTime.now());
        call.setOutput("{}");
        stubView(null, call);

        SessionToolCallResponse response = service.get(USER_ID, SESSION_ID, "call-1");

        assertNull(response.viewUri());
        assertNull(response.input());
        assertNull(response.result());
    }

    @Test
    @DisplayName("вью на туле модели резолвится: VIEW нужен тулам панели, а не тулу показа")
    void modelToolWithViewResolves() {
        ToolCallLog call = call();
        stubView(new ToolUi(VIEW_URI, List.of("model")), call);

        assertEquals(VIEW_URI, service.get(USER_ID, SESSION_ID, "call-1").viewUri());
    }

    @Test
    @DisplayName("чужая сессия — Forbidden")
    void foreignSession() {
        session.setUserId(UUID.randomUUID());

        assertThrows(ForbiddenStatusException.class, () -> service.get(USER_ID, SESSION_ID, "call-1"));
    }

    @Test
    @DisplayName("вызов другой сессии того же агента — NotFound")
    void callOfAnotherSession() {
        ToolCallLog call = call();
        call.setAgentSessionId(UUID.randomUUID().toString());
        when(agentViewService.ownedAgent(AGENT_ID, USER_ID)).thenReturn(agent);
        when(toolCallLogService.findByExternalIdAndAgentId("call-1", AGENT_ID))
                .thenReturn(Optional.of(call));

        assertThrows(NotFoundStatusException.class, () -> service.get(USER_ID, SESSION_ID, "call-1"));
    }

    @Test
    @DisplayName("неизвестный вызов — NotFound")
    void unknownCall() {
        when(agentViewService.ownedAgent(AGENT_ID, USER_ID)).thenReturn(agent);
        when(toolCallLogService.findByExternalIdAndAgentId("call-1", AGENT_ID))
                .thenReturn(Optional.empty());

        assertThrows(NotFoundStatusException.class, () -> service.get(USER_ID, SESSION_ID, "call-1"));
    }
}
