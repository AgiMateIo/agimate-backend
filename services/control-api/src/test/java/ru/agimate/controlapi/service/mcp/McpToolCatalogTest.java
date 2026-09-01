package ru.agimate.controlapi.service.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.agimate.controlapi.abac.AccessDecision;
import ru.agimate.controlapi.abac.ConnectionAccessEvaluator;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.JsonSchema;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.enums.ConnectionAuthStatus;
import ru.agimate.controlapi.database.enums.DefinitionBinding;
import ru.agimate.controlapi.database.enums.PolicyKind;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.service.tool.ToolDefinitionService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("McpToolCatalog — что видит MCP-клиент")
class McpToolCatalogTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();

    @Mock
    private AgentConnectionRepository agentConnectionRepository;
    @Mock
    private ConnectionRepository connectionRepository;
    @Mock
    private ConnectorRepository connectorRepository;
    @Mock
    private ToolDefinitionService toolDefinitionService;
    @Mock
    private ConnectionAccessEvaluator accessEvaluator;
    @Mock
    private ConnectorRegistry connectorRegistry;

    @InjectMocks
    private McpToolCatalog catalog;

    private final Agent agent = Agent.builder()
            .id(AGENT_ID).userId(USER_ID).name("mcp").type(AgentType.MCP).build();

    private final Connection connection = Connection.builder()
            .id(CONNECTION_ID)
            .connectorCode("mcp")
            .fullCode("mcp_context7")
            .userId(USER_ID)
            .enabled(true)
            .build();

    @BeforeEach
    void setUp() {
        when(agentConnectionRepository.findActiveByAgentId(AGENT_ID)).thenReturn(List.of(
                AgentConnection.builder().agentId(AGENT_ID).connectionId(CONNECTION_ID).build()));
        when(connectionRepository.findByIdNotDeleted(CONNECTION_ID)).thenReturn(Optional.of(connection));
        when(connectorRepository.findById("mcp")).thenReturn(Optional.of(Connector.builder()
                .code("mcp")
                .definitionBinding(DefinitionBinding.DYNAMIC)
                .build()));
        when(accessEvaluator.evaluate(eq(AGENT_ID), any(UUID.class), eq(PolicyKind.TOOL), any()))
                .thenReturn(AccessDecision.allow(null));
    }

    private static ConnectorToolSpec spec(String name) {
        return new ConnectorToolSpec(name, null, name, JsonSchema.any(null), null, null, null, null);
    }

    private void connectionTools(String... names) {
        Map<String, ConnectorToolSpec> tools = new LinkedHashMap<>();
        for (String name : names) {
            tools.put(name, spec(name));
        }
        when(toolDefinitionService.getTools(USER_ID, "mcp", CONNECTION_ID)).thenReturn(tools);
    }

    @Test
    @DisplayName("имя = full_code + __ + тул, символы вне [a-zA-Z0-9_-] заменяются")
    void namesArePrefixedAndSanitized() {
        connectionTools("resolve-library-id", "tool.device.tts.speak");

        Map<String, McpToolCatalog.ToolEntry> result = catalog.forAgent(agent);

        assertTrue(result.containsKey("mcp_context7__resolve-library-id"));
        assertTrue(result.containsKey("mcp_context7__tool_device_tts_speak"));
        assertEquals("resolve-library-id", result.get("mcp_context7__resolve-library-id").toolName());
        assertEquals(CONNECTION_ID, result.get("mcp_context7__resolve-library-id").connectionId());
    }

    @Test
    @DisplayName("тул под DENY в каталог не попадает")
    void deniedToolIsNotListed() {
        connectionTools("get-docs", "delete-everything");
        when(accessEvaluator.evaluate(AGENT_ID, CONNECTION_ID, PolicyKind.TOOL, "delete-everything"))
                .thenReturn(AccessDecision.deny("nope"));

        Map<String, McpToolCatalog.ToolEntry> result = catalog.forAgent(agent);

        assertTrue(result.containsKey("mcp_context7__get-docs"));
        assertFalse(result.containsKey("mcp_context7__delete-everything"));
    }

    @Test
    @DisplayName("коннекшен без завершённой авторизации тулов не даёт")
    void unusableConnectionIsSkipped() {
        connection.setAuthStatus(ConnectionAuthStatus.PENDING_AUTH);
        connectionTools("get-docs");

        assertTrue(catalog.forAgent(agent).isEmpty());
    }

    @Test
    @DisplayName("два имени, схлопывающиеся в одно после санитизации → второе не подменяет первое")
    void nameClashKeepsTheFirst() {
        connectionTools("get.docs", "get_docs");

        Map<String, McpToolCatalog.ToolEntry> result = catalog.forAgent(agent);

        assertEquals(1, result.size());
        assertEquals("get.docs", result.get("mcp_context7__get_docs").toolName());
    }

    @Test
    @DisplayName("платформенный коннектор (mode-строка, STATIC) отдаёт админ-тулы в каталог")
    void platformConnectorToolsAreListed() {
        connectionTools("get-docs");
        UUID platformConnectionId = UUID.randomUUID();
        Connection platformConnection = Connection.builder()
                .id(platformConnectionId)
                .connectorCode("platform")
                .fullCode("platform_" + USER_ID)
                .userId(USER_ID)
                .enabled(true)
                .build();
        when(agentConnectionRepository.findActiveByAgentId(AGENT_ID)).thenReturn(List.of(
                AgentConnection.builder().agentId(AGENT_ID).connectionId(CONNECTION_ID).build(),
                AgentConnection.builder().agentId(AGENT_ID).connectionId(platformConnectionId).build()));
        when(connectionRepository.findByIdNotDeleted(platformConnectionId))
                .thenReturn(Optional.of(platformConnection));
        when(connectorRepository.findById("platform")).thenReturn(Optional.of(Connector.builder()
                .code("platform")
                .definitionBinding(DefinitionBinding.STATIC)
                .build()));
        when(connectorRegistry.findHandler("platform"))
                .thenReturn(Optional.of(mock(InternalConnectorHandler.class)));
        Map<String, ConnectorToolSpec> platformTools = new LinkedHashMap<>();
        for (String name : List.of("delete_agent", "create_channel", "list_llm_providers", "list_runs")) {
            platformTools.put(name, spec(name));
        }
        when(toolDefinitionService.getTools(USER_ID, "platform", platformConnectionId))
                .thenReturn(platformTools);

        Map<String, McpToolCatalog.ToolEntry> result = catalog.forAgent(agent);

        // Internal mode rows are named by connector code (like in the agent's own context) — so the
        // platform admin tools keep their plain names over MCP instead of a truncated uuid-prefixed form.
        assertTrue(result.containsKey("platform__delete_agent"));
        assertTrue(result.containsKey("platform__create_channel"));
        assertTrue(result.containsKey("platform__list_llm_providers"));
        assertTrue(result.containsKey("platform__list_runs"));
        // The MCP connection's tools are still there — the catalog merges all bindings.
        assertTrue(result.containsKey("mcp_context7__get-docs"));
    }
}
