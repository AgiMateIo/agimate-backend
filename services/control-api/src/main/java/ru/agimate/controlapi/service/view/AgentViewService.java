package ru.agimate.controlapi.service.view;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.CustomResponseStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.TooManyRequestsStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.common.util.UUIDUtils;
import ru.agimate.controlapi.connectors.core.ConnectorEnvFactory;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.dto.ToolUi;
import ru.agimate.controlapi.connectors.core.execution.ToolExecutionService;
import ru.agimate.controlapi.connectors.integrations.mcp.McpClient;
import ru.agimate.controlapi.connectors.integrations.mcp.McpConnectorService;
import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;
import ru.agimate.controlapi.controller.manage.dto.AgentViewContentResponse;
import ru.agimate.controlapi.controller.manage.dto.AgentViewResponse;
import ru.agimate.controlapi.controller.manage.dto.ViewToolCallRequest;
import ru.agimate.controlapi.controller.manage.dto.ViewToolCallResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.service.AgentService;
import ru.agimate.controlapi.service.dto.ToolResult;
import ru.agimate.controlapi.service.mcp.McpToolCatalog;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter;
import ru.agimate.controlapi.service.tool.AgentToolCallService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Connector views of an agent (docs/decisions/connector-views.md): which views its bindings give it,
 * the page of one, and the tool calls that page makes. Everything is derived from
 * {@link McpToolCatalog}, so bindings and ABAC decide here exactly as they do for the agent itself,
 * and a call from a view runs <i>as the agent</i> — the same {@link AgentToolCallService} path as
 * {@code /mcp}.
 *
 * <p>Not transactional: reading a view and calling a tool go over the network, and the pieces that
 * touch the database carry their own transactions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentViewService {

    /**
     * A view call holds a user's HTTP request open while a button waits. No tasks: a view has no
     * conversation to deliver a late result to, and the call still finishes into the log.
     */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    private final AgentService agentService;
    private final McpToolCatalog toolCatalog;
    private final ConnectionRepository connectionRepository;
    private final ConnectorEnvFactory connectorEnvFactory;
    private final McpConnectorService mcpConnectorService;
    private final AgentToolCallService agentToolCallService;
    private final ToolExecutionService toolExecutionService;
    private final InboundRateLimiter rateLimiter;

    /** Grouped by {@code (connection, uri)}: several tools of one server commonly render into one view. */
    public List<AgentViewResponse> list(UUID agentId, UUID userId) {
        Agent agent = ownedAgent(agentId, userId);
        record Key(UUID connectionId, String uri) {}
        Map<Key, List<McpToolCatalog.ToolEntry>> views = new LinkedHashMap<>();
        for (McpToolCatalog.ToolEntry entry : toolCatalog.forAgent(agent).values()) {
            ToolUi ui = entry.spec().ui();
            if (ui != null && ui.resourceUri() != null && servesViews(entry.connectorCode())) {
                views.computeIfAbsent(new Key(entry.connectionId(), ui.resourceUri()), k -> new ArrayList<>())
                        .add(entry);
            }
        }
        return views.entrySet().stream()
                .map(view -> new AgentViewResponse(
                        view.getKey().connectionId(),
                        view.getValue().getFirst().connectorCode(),
                        connectionRepository.findByIdNotDeleted(view.getKey().connectionId())
                                .map(Connection::getName)
                                .orElse(null),
                        view.getKey().uri(),
                        view.getValue().stream().map(McpToolCatalog.ToolEntry::toolName).toList()))
                .toList();
    }

    /**
     * The server's {@code _meta.ui.csp} is dropped: a foreign page inside our interface gets no external
     * domains at all — neither to connect to nor to load from, since an image URL leaks as well as a fetch.
     *
     * <p>Only a uri some allowed tool of this connection links to is read: the endpoint must not become
     * a proxy for arbitrary {@code resources/read} on a server holding the user's credentials.
     */
    public AgentViewContentResponse content(UUID agentId, UUID userId, UUID connectionId, String uri) {
        Agent agent = ownedAgent(agentId, userId);
        boolean declared = toolCatalog.forAgent(agent).values().stream()
                .anyMatch(entry -> entry.connectionId().equals(connectionId)
                        && servesViews(entry.connectorCode())
                        && entry.spec().ui() != null
                        && uri.equals(entry.spec().ui().resourceUri()));
        if (!declared) {
            throw new NotFoundStatusException("View not found");
        }
        Connection connection = connectionRepository.findByIdNotDeleted(connectionId)
                .orElseThrow(() -> new NotFoundStatusException("View not found"));

        McpClient.Resource resource;
        try {
            resource = mcpConnectorService.readResource(
                    connectorEnvFactory.forConnection(connection, agent.getId(), null, null), uri);
        } catch (ConnectorException e) {
            throw badGateway(e.getMessage());
        }
        if (!isViewMimeType(resource.mimeType())) {
            throw badGateway("The server returned " + resource.mimeType() + " instead of an MCP App page");
        }
        JsonNode ui = resource.meta() == null ? null : resource.meta().get("ui");
        return new AgentViewContentResponse(
                uri,
                resource.mimeType(),
                resource.text(),
                objectOrNull(ui, "permissions"),
                ui != null && ui.path("prefersBorder").isBoolean() ? ui.get("prefersBorder").asBoolean() : null);
    }

    /**
     * Two gates on top of the agent's own: the tool must be one of this connection in the agent's
     * catalog (bindings and ABAC), and its server must have declared it callable from a view.
     */
    public ViewToolCallResponse call(UUID agentId, UUID userId, ViewToolCallRequest request) {
        Agent agent = ownedAgent(agentId, userId);
        if (!rateLimiter.tryAcquire(InboundRateLimiter.Scope.VIEW_CALL, agent.getId())) {
            throw new TooManyRequestsStatusException("View call rate limit exceeded");
        }
        McpToolCatalog.ToolEntry entry = toolCatalog.forAgent(agent).values().stream()
                .filter(e -> e.connectionId().equals(request.connectionId())
                        && e.toolName().equals(request.name()))
                .findFirst()
                .orElseThrow(() -> new NotFoundStatusException("Tool not found"));
        ToolUi ui = entry.spec().ui();
        if (ui == null || !ui.callableFromView()) {
            throw new ForbiddenStatusException("Tool '" + request.name() + "' is not callable from a view");
        }

        ToolCallRequest call = ToolCallRequest.builder()
                .id(UUIDUtils.generateUUIDv8().toString())
                .connectorCode(entry.connectorCode())
                .connectionId(entry.connectionId().toString())
                .name(entry.toolName())
                .input(request.arguments() == null ? Map.of() : request.arguments())
                .build();

        ToolCallLog toolCallLog;
        try {
            toolCallLog = agentToolCallService.authorizeToolCall(agent.getId(), call);
        } catch (ForbiddenStatusException e) {
            // params_filter refused these arguments: the view asked for something the agent may not do.
            return ViewToolCallResponse.error(e.getMessage());
        }

        ToolExecutionService.WaitOutcome outcome = toolExecutionService.executeWithTimeout(toolCallLog, CALL_TIMEOUT);
        if (outcome instanceof ToolExecutionService.WaitOutcome.Completed completed) {
            return toResponse(completed.result());
        }
        return ViewToolCallResponse.error("Tool execution timed out after " + CALL_TIMEOUT.toSeconds() + "s");
    }

    private Agent ownedAgent(UUID agentId, UUID userId) {
        Agent agent = agentService.findById(agentId);
        if (!agent.getUserId().equals(userId)) {
            throw new NotFoundStatusException("Agent not found");
        }
        return agent;
    }

    /** Only MCP servers serve views so far; internal connectors will get their own reader. */
    private static boolean servesViews(String connectorCode) {
        return McpConnectorService.CONNECTOR_CODE.equals(connectorCode);
    }

    static boolean isViewMimeType(String mimeType) {
        return mimeType != null
                && mimeType.replace(" ", "").toLowerCase().startsWith("text/html;")
                && mimeType.replace(" ", "").toLowerCase().contains("profile=mcp-app");
    }

    /**
     * The MCP connector records the whole {@code CallToolResult} as the log's output, so the view gets
     * {@code structuredContent} back instead of the text flattening the agent sees.
     */
    static ViewToolCallResponse toResponse(ToolResult result) {
        if (result.getError() != null) {
            return ViewToolCallResponse.error(result.getError());
        }
        JsonNode output = result.getOutput() == null ? null : JsonUtils.toJsonNodeOrNull(result.getOutput());
        if (output == null || !output.isObject() || !output.has("content")) {
            String text = result.getOutput() == null ? "" : result.getOutput();
            return new ViewToolCallResponse(List.of(Map.of("type", "text", "text", text)), null, false);
        }
        Map<String, Object> map = JsonUtils.MAPPER.convertValue(output, JsonUtils.MAP_TYPE_REFERENCE);
        return new ViewToolCallResponse(
                map.get("content") instanceof List<?> content ? new ArrayList<>(content) : List.of(),
                map.get("structuredContent"),
                Boolean.TRUE.equals(map.get("isError")));
    }

    private static Map<String, Object> objectOrNull(JsonNode ui, String field) {
        JsonNode node = ui == null ? null : ui.get(field);
        return node != null && node.isObject() ? JsonUtils.MAPPER.convertValue(node, JsonUtils.MAP_TYPE_REFERENCE) : null;
    }

    private static CustomResponseStatusException badGateway(String message) {
        return new CustomResponseStatusException(502, message, CustomResponseStatusException.LoggingLevel.WARN);
    }
}
