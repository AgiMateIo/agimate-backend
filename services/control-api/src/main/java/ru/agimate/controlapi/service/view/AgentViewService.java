package ru.agimate.controlapi.service.view;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.CustomResponseStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.TooManyRequestsStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorEnvFactory;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.ViewProvider;
import ru.agimate.controlapi.connectors.core.dto.ToolAudience;
import ru.agimate.controlapi.connectors.core.dto.ToolUi;
import ru.agimate.controlapi.connectors.core.dto.ViewCallResult;
import ru.agimate.controlapi.connectors.core.dto.ViewPage;
import ru.agimate.controlapi.controller.manage.dto.AgentViewContentResponse;
import ru.agimate.controlapi.controller.manage.dto.AgentViewResponse;
import ru.agimate.controlapi.controller.manage.dto.ViewToolCallRequest;
import ru.agimate.controlapi.controller.manage.dto.ViewToolCallResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.Connection;
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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Connector views of an agent (docs/decisions/connector-views.md): which views its bindings give it,
 * the page of one, and the tool calls that page makes. Everything is derived from
 * {@link McpToolCatalog}, so bindings and ABAC decide here exactly as they do for the agent itself,
 * and a call from a view runs <i>as the agent</i> — {@link AgentToolCallService#callAsAgent}, the same
 * path as {@code /mcp}.
 *
 * <p>Not transactional: reading a view and calling a tool go over the network, and the pieces that
 * touch the database carry their own transactions.
 */
@Slf4j
@Component
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
    private final ConnectorRegistry connectorRegistry;
    private final ConnectorEnvFactory connectorEnvFactory;
    private final AgentToolCallService agentToolCallService;
    private final InboundRateLimiter rateLimiter;

    /** Grouped by {@code (connection, uri)}: several tools of one server commonly render into one view. */
    public List<AgentViewResponse> list(UUID agentId, UUID userId) {
        Agent agent = ownedAgent(agentId, userId);
        record Key(UUID connectionId, String uri) {}
        Map<Key, List<McpToolCatalog.ToolEntry>> views = new LinkedHashMap<>();
        for (McpToolCatalog.ToolEntry entry : viewCatalog(agent)) {
            if (linksView(entry)) {
                views.computeIfAbsent(new Key(entry.connectionId(), entry.spec().ui().resourceUri()),
                        k -> new ArrayList<>()).add(entry);
            }
        }
        Map<UUID, String> names = connectionRepository.findByIdInNotDeleted(
                        views.keySet().stream().map(Key::connectionId).distinct().toList()).stream()
                .filter(connection -> connection.getName() != null)
                .collect(Collectors.toMap(Connection::getId, Connection::getName));
        return views.entrySet().stream()
                .map(view -> new AgentViewResponse(
                        view.getKey().connectionId(),
                        view.getValue().getFirst().connectorCode(),
                        names.get(view.getKey().connectionId()),
                        view.getKey().uri(),
                        view.getValue().stream().map(McpToolCatalog.ToolEntry::toolName).toList()))
                .toList();
    }

    /**
     * Only a uri some allowed tool of this connection links to is read: the endpoint must not become
     * a proxy for arbitrary reads on a server holding the user's credentials.
     */
    public AgentViewContentResponse content(UUID agentId, UUID userId, UUID connectionId, String uri) {
        Agent agent = ownedAgent(agentId, userId);
        McpToolCatalog.ToolEntry entry = viewCatalog(agent).stream()
                .filter(e -> e.connectionId().equals(connectionId)
                        && linksView(e)
                        && uri.equals(e.spec().ui().resourceUri()))
                .findFirst()
                .orElseThrow(() -> new NotFoundStatusException("View not found"));
        Connection connection = connectionRepository.findByIdNotDeleted(connectionId)
                .orElseThrow(() -> new NotFoundStatusException("View not found"));

        ViewPage page;
        try {
            page = viewProvider(entry).readView(
                    connectorEnvFactory.forConnection(connection, agent.getId(), null, null), uri);
        } catch (ConnectorException e) {
            throw new CustomResponseStatusException(502, e.getMessage(), CustomResponseStatusException.LoggingLevel.WARN);
        }
        return new AgentViewContentResponse(uri, page.mimeType(), page.html(), page.permissions(), page.prefersBorder());
    }

    /**
     * Gates on top of the agent's own: the tool must be one of this connection in the agent's catalog
     * (bindings and ABAC), the connection must serve a view at all, and the tool's visibility must admit
     * views. The catalog is listed wide so a tool declared for the model only is a 403 the host can tell
     * apart from a missing one.
     */
    public ViewToolCallResponse call(UUID agentId, UUID userId, ViewToolCallRequest request) {
        Agent agent = ownedAgent(agentId, userId);
        if (!rateLimiter.tryAcquire(InboundRateLimiter.Scope.VIEW_CALL, agent.getId())) {
            throw new TooManyRequestsStatusException("View call rate limit exceeded");
        }
        List<McpToolCatalog.ToolEntry> connectionTools = viewCatalog(agent).stream()
                .filter(e -> e.connectionId().equals(request.connectionId()))
                .toList();
        McpToolCatalog.ToolEntry entry = connectionTools.stream()
                .filter(e -> e.toolName().equals(request.name()))
                .findFirst()
                // A connection without views has no page that could be asking, whatever its tools declare.
                .filter(e -> connectionTools.stream().anyMatch(AgentViewService::linksView))
                .orElseThrow(() -> new NotFoundStatusException("Tool not found"));
        if (!ToolUi.visibleTo(entry.spec().ui(), ToolAudience.VIEW)) {
            throw new ForbiddenStatusException("Tool '" + request.name() + "' is not callable from a view");
        }

        return switch (agentToolCallService.callAsAgent(agent.getId(), entry.connectorCode(),
                entry.connectionId(), entry.toolName(), request.arguments(), CALL_TIMEOUT)) {
            case AgentToolCallService.CallOutcome.Refused(var message) -> ViewToolCallResponse.of(ViewCallResult.error(message));
            case AgentToolCallService.CallOutcome.Completed(var result) -> ViewToolCallResponse.of(toViewResult(entry, result));
            case AgentToolCallService.CallOutcome.StillRunning(var ignored) -> ViewToolCallResponse.of(ViewCallResult.error(
                    "Tool execution timed out after " + CALL_TIMEOUT.toSeconds() + "s"));
        };
    }

    private Agent ownedAgent(UUID agentId, UUID userId) {
        Agent agent = agentService.findById(agentId);
        if (!agent.getUserId().equals(userId)) {
            throw new NotFoundStatusException("Agent not found");
        }
        return agent;
    }

    /** The agent's tools of connections whose connector serves views; listed wide, see {@link #call}. */
    private List<McpToolCatalog.ToolEntry> viewCatalog(Agent agent) {
        Map<String, Optional<ViewProvider>> providers = new LinkedHashMap<>();
        return toolCatalog.forAgent(agent, ToolAudience.ALL).values().stream()
                .filter(entry -> providers.computeIfAbsent(entry.connectorCode(),
                        code -> connectorRegistry.findCapability(code, ViewProvider.class)).isPresent())
                .toList();
    }

    private static boolean linksView(McpToolCatalog.ToolEntry entry) {
        return entry.spec().ui() != null && entry.spec().ui().resourceUri() != null;
    }

    private ViewProvider viewProvider(McpToolCatalog.ToolEntry entry) {
        return connectorRegistry.findCapability(entry.connectorCode(), ViewProvider.class)
                .orElseThrow(() -> new NotFoundStatusException("View not found"));
    }

    private ViewCallResult toViewResult(McpToolCatalog.ToolEntry entry, ToolResult result) {
        return result.getError() != null
                ? ViewCallResult.error(result.getError())
                : viewProvider(entry).toViewResult(result.getOutput());
    }
}
