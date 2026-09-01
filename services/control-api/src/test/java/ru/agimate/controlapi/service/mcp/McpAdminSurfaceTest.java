package ru.agimate.controlapi.service.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.abac.AccessDecision;
import ru.agimate.controlapi.abac.ConnectionAccessEvaluator;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;

import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.execution.ToolExecutionService;
import ru.agimate.controlapi.connectors.internal.platform.PlatformAgentToolService;
import ru.agimate.controlapi.connectors.internal.platform.PlatformConnectionToolService;
import ru.agimate.controlapi.connectors.internal.platform.PlatformConnectorService;
import ru.agimate.controlapi.connectors.internal.platform.PlatformLlmToolService;
import ru.agimate.controlapi.connectors.internal.platform.PlatformObservabilityToolService;
import ru.agimate.controlapi.connectors.internal.platform.PlatformWorkspaceToolService;
import ru.agimate.controlapi.controller.mcp.dto.JsonRpcRequest;
import ru.agimate.controlapi.controller.mcp.dto.JsonRpcResponse;
import ru.agimate.controlapi.controller.mcp.dto.McpTool;
import ru.agimate.controlapi.controller.mcp.dto.ToolCallResult;
import ru.agimate.controlapi.controller.mcp.dto.ToolsListResult;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.enums.DefinitionBinding;
import ru.agimate.controlapi.database.enums.PolicyKind;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.AgentLlmRepository;
import ru.agimate.controlapi.database.repositories.AgentPresetRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentRunTurnRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.AgenticTeamRepository;
import ru.agimate.controlapi.database.repositories.BoardRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.ConnectorJobRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderCatalogRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderModelRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.database.repositories.LlmQuotaRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.database.repositories.StoredFileRepository;
import ru.agimate.controlapi.database.repositories.ToolCallLogRepository;
import ru.agimate.controlapi.database.repositories.TriggerLogRepository;
import ru.agimate.controlapi.database.repositories.WebhookDeliveryLogRepository;
import ru.agimate.controlapi.service.AgentService;
import ru.agimate.controlapi.service.AgentSkillService;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;
import ru.agimate.controlapi.service.SkillService;
import ru.agimate.controlapi.service.AgentLlmService;
import ru.agimate.controlapi.service.channel.ChannelService;
import ru.agimate.controlapi.service.connection.ConnectionService;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter;
import ru.agimate.controlapi.service.session.AgentSessionService;
import ru.agimate.controlapi.service.tool.AgentToolCallService;
import ru.agimate.controlapi.service.tool.ToolCallLogService;
import ru.agimate.controlapi.service.tool.ToolDefinitionService;
import ru.agimate.controlapi.service.trigger.RunCancellationService;
import ru.agimate.controlapi.service.dto.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The platform-admin surface over MCP, wired for real: {@link McpService} → {@link McpToolCatalog} →
 * {@link ToolDefinitionService} → {@link ConnectorRegistry} → {@link PlatformConnectorService} — the
 * same chain a live client walks. Only persistence and the outbound executor are mocked: the catalog
 * reads the agent's bindings from mocked repositories, and the executor answer invokes the real
 * connector with a {@link ConnectorEnv} built from the call log. This is what the manual smoke run
 * proved against a live instance — encoded here so the surface cannot regress unnoticed.
 */
@DisplayName("MCP admin surface — каталог и диспетчеризация platform-коннектора")
class McpAdminSurfaceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();

    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final AgentSkillRepository agentSkillRepository = mock(AgentSkillRepository.class);
    private final SkillRepository skillRepository = mock(SkillRepository.class);
    private final StoredFileRepository storedFileRepository = mock(StoredFileRepository.class);
    private final ConnectorRepository connectorRepository = mock(ConnectorRepository.class);
    private final ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
    private final AgentConnectionRepository agentConnectionRepository = mock(AgentConnectionRepository.class);
    private final ConnectionToolRepository connectionToolRepository = mock(ConnectionToolRepository.class);
    private final ConnectionAccessEvaluator accessEvaluator = mock(ConnectionAccessEvaluator.class);

    private final ConnectionBindingService connectionBindingService = mock(ConnectionBindingService.class);
    private final AgentSkillService agentSkillService = mock(AgentSkillService.class);

    private final PlatformAgentToolService agentTools = new PlatformAgentToolService(
            agentRepository, agentSkillRepository, skillRepository, storedFileRepository,
            mock(AgentService.class), mock(SkillService.class), agentSkillService,
            mock(ru.agimate.controlapi.service.file.UserFileService.class), connectionBindingService);
    private final PlatformConnectionToolService connectionTools = new PlatformConnectionToolService(
            connectorRepository, connectionRepository, agentConnectionRepository,
            mock(ConnectorRegistry.class), mock(ToolDefinitionService.class),
            mock(ConnectionService.class), mock(ConnectionBindingService.class),
            mock(ru.agimate.controlapi.abac.AgentConnectionPolicyService.class), mock(ChannelService.class));
    private final PlatformLlmToolService llmTools = new PlatformLlmToolService(
            mock(LlmProviderRepository.class), mock(LlmProviderModelRepository.class),
            mock(LlmProviderCatalogRepository.class), mock(AgentLlmRepository.class), agentRepository,
            mock(ru.agimate.controlapi.service.LlmProviderService.class),
            mock(ru.agimate.controlapi.service.llm.LlmQuotaService.class),
            mock(AgentLlmService.class), mock(ru.agimate.controlapi.service.llm.LlmUsageQueryService.class));
    private final PlatformWorkspaceToolService workspaceTools = new PlatformWorkspaceToolService(
            mock(AgenticTeamRepository.class), mock(AgentPresetRepository.class),
            mock(BoardRepository.class), mock(ConnectorJobRepository.class), agentRepository,
            mock(ru.agimate.controlapi.service.AgenticTeamService.class),
            mock(ru.agimate.controlapi.service.board.BoardService.class),
            mock(ru.agimate.controlapi.service.ConnectorJobManageService.class));
    private final PlatformObservabilityToolService observabilityTools = new PlatformObservabilityToolService(
            mock(AgentRunRepository.class), mock(AgentRunTurnRepository.class),
            mock(AgentSessionService.class), mock(ToolCallLogRepository.class),
            mock(TriggerLogRepository.class), mock(WebhookDeliveryLogRepository.class),
            mock(RunCancellationService.class));

    private final PlatformConnectorService platformConnector = new PlatformConnectorService(
            agentTools, connectionTools, llmTools, workspaceTools, observabilityTools);
    private final ConnectorRegistry registry = new ConnectorRegistry(Set.of(platformConnector));
    private final ToolDefinitionService toolDefinitionService = new ToolDefinitionService(
            connectorRepository, registry, connectionRepository, connectionToolRepository);
    private final McpToolCatalog catalog = new McpToolCatalog(
            agentConnectionRepository, connectionRepository, connectorRepository,
            toolDefinitionService, accessEvaluator, registry);

    private final AgentService agentService = mock(AgentService.class);
    private final AgentToolCallService agentToolCallService = mock(AgentToolCallService.class);
    private final ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
    private final ToolCallLogService toolCallLogService = mock(ToolCallLogService.class);
    private final InboundRateLimiter rateLimiter = mock(InboundRateLimiter.class);

    private final McpService mcpService = new McpService(
            agentService, catalog, agentToolCallService, toolExecutionService, toolCallLogService, rateLimiter);

    private final Agent agent = Agent.builder()
            .id(AGENT_ID).userId(USER_ID).name("mcp-admin").type(AgentType.MCP).build();
    private final Connection platformConnection = Connection.builder()
            .id(CONNECTION_ID)
            .connectorCode("platform")
            .fullCode("platform_" + USER_ID)
            .userId(USER_ID)
            .enabled(true)
            .build();

    @BeforeEach
    void setUp() {
        when(agentConnectionRepository.findActiveByAgentId(AGENT_ID)).thenReturn(List.of(
                AgentConnection.builder().agentId(AGENT_ID).connectionId(CONNECTION_ID).build()));
        when(connectionRepository.findByIdNotDeleted(CONNECTION_ID))
                .thenReturn(Optional.of(platformConnection));
        when(connectorRepository.findById("platform")).thenReturn(Optional.of(Connector.builder()
                .code("platform")
                .definitionBinding(DefinitionBinding.STATIC)
                .build()));
        when(accessEvaluator.evaluate(eq(AGENT_ID), any(UUID.class), eq(PolicyKind.TOOL), any()))
                .thenReturn(AccessDecision.allow(null));
        when(rateLimiter.tryAcquire(any(), eq(AGENT_ID))).thenReturn(true);
        when(agentService.findById(AGENT_ID)).thenReturn(agent);
        when(toolCallLogService.countLiveDetached(any(), any())).thenReturn(0L);
    }

    private JsonRpcResponse handle(String method, Map<String, Object> params) {
        return mcpService.handle(
                new ru.agimate.controlapi.security.AgentPrincipal(agent.getName(), AGENT_ID, USER_ID),
                new JsonRpcRequest("2.0", "1", method, params)).orElseThrow();
    }

    @Test
    @DisplayName("tools/list отдаёт все 79 тулов platform-коннектора под коротким хендлом")
    void listsTheWholePlatformSurface() {
        JsonRpcResponse response = handle("tools/list", Map.of());

        assertFalse(response.error() != null);
        ToolsListResult result = (ToolsListResult) response.result();
        assertEquals(79, result.tools().size());
        assertTrue(result.tools().stream().map(McpTool::name).allMatch(n -> n.startsWith("platform__")));
        assertTrue(result.tools().stream().map(McpTool::name)
                .anyMatch(n -> n.equals("platform__delete_agent")));
        assertTrue(result.tools().stream().map(McpTool::name)
                .anyMatch(n -> n.equals("platform__list_llm_providers")));
        assertTrue(result.tools().stream().map(McpTool::name)
                .anyMatch(n -> n.equals("platform__get_run_turns")));
    }

    @Test
    @DisplayName("tools/call list_agents исполняется реальным коннектором и возвращает владельца")
    void callsAListingToolThroughTheRealConnector() {
        when(agentRepository.findByUserId(any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(agent)));
        stubExecution("list_agents", Map.of());

        JsonRpcResponse response = handle("tools/call",
                Map.of("name", "platform__list_agents", "arguments", Map.of()));

        ToolCallResult result = (ToolCallResult) response.result();
        assertNotNull(result);
        assertTrue(String.valueOf(result.structuredContent()).contains(agent.getName()));
    }

    @Test
    @DisplayName("self-guard срабатывает сквозь весь MCP-путь")
    void selfGuardFiresThroughTheMcpChain() {
        stubExecution("update_agent", Map.of("agentId", AGENT_ID.toString(), "name", "renamed"));

        JsonRpcResponse response = handle("tools/call",
                Map.of("name", "platform__update_agent",
                        "arguments", Map.of("agentId", AGENT_ID.toString(), "name", "renamed")));

        ToolCallResult result = (ToolCallResult) response.result();
        assertTrue(result.isError());
        assertTrue(String.valueOf(result.content().getFirst().text()).contains("cannot manage itself"));
    }

    @Test
    @DisplayName("неизвестный тул — -32602, как и положено на транспорте")
    void unknownToolIsInvalidParams() {
        JsonRpcResponse response = handle("tools/call",
                Map.of("name", "platform__no_such_tool", "arguments", Map.of()));

        assertTrue(response.error() != null);
        assertEquals(-32602, response.error().code());
    }

    /**
     * The executor is the one piece the test does not reconstruct: its answer runs the real
     * connector instead, with a {@link ConnectorEnv} assembled from the call log — mirroring what
     * {@code ToolExecutionService} does for a BACKEND connector.
     */
    private void stubExecution(String toolName, Map<String, Object> arguments) {
        when(agentToolCallService.authorizeToolCall(eq(AGENT_ID), any())).thenAnswer(inv -> {
            var request = inv.getArgument(1, ru.agimate.controlapi.controller.agent.dto.ToolCallRequest.class);
            return ToolCallLog.builder()
                    .id(UUID.randomUUID())
                    .userId(USER_ID)
                    .agentId(AGENT_ID)
                    .connectorCode("platform")
                    .connectionId(CONNECTION_ID.toString())
                    .name(request.getName())
                    .build();
        });
        when(toolExecutionService.executeWithTimeout(any(), any())).thenAnswer(inv -> {
            ToolCallLog log = inv.getArgument(0);
            ConnectorEnv env = new ConnectorEnv(log.getConnectionId(), USER_ID, AGENT_ID,
                    null, null, null, Map.of(), null);
            var provider = registry.findCapability(log.getConnectorCode(),
                    ru.agimate.controlapi.connectors.core.ToolProvider.class).orElseThrow();
            // The real executor turns a ConnectorException into a failed tool result instead of
            // letting it escape — mirrored here so the MCP layer sees the same shape as in prod.
            try {
                Map<String, Object> output = provider.executeTool(env, log.getName(), arguments);
                String json = ru.agimate.common.util.JsonUtils.writeValueAsString(output);
                return new ToolExecutionService.WaitOutcome.Completed(
                        new ToolResult(log.getExternalId() == null ? "ext" : log.getExternalId(),
                                log.getConnectorCode(), json, null));
            } catch (ru.agimate.controlapi.connectors.core.ConnectorException e) {
                return new ToolExecutionService.WaitOutcome.Completed(
                        new ToolResult(log.getExternalId() == null ? "ext" : log.getExternalId(),
                                log.getConnectorCode(), null, e.getMessage()));
            }
        });
    }
}
