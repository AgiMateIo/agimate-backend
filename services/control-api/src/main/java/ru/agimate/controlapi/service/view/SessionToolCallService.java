package ru.agimate.controlapi.service.view;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.dto.ViewCallResult;
import ru.agimate.controlapi.controller.manage.dto.session.SessionToolCallResponse;
import ru.agimate.controlapi.controller.manage.dto.session.ToolCallDescriptor;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.service.mcp.McpToolCatalog;
import ru.agimate.controlapi.service.session.AgentSessionService;
import ru.agimate.controlapi.service.tool.ToolCallLogService;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a tool call named by a session message into the inline view it renders into — the
 * read-time half of docs/decisions/connector-views.md: the message stores only the id, and the view,
 * its input and its result are assembled here from {@code tool_call_logs} and the agent's current
 * catalog.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionToolCallService {

    private final AgentSessionService agentSessionService;
    private final AgentViewService agentViewService;
    private final ToolCallLogService toolCallLogService;

    /**
     * @throws ForbiddenStatusException the session belongs to someone else
     * @throws NotFoundStatusException  no such call of this session
     */
    public SessionToolCallResponse get(UUID userId, UUID sessionId, String toolCallId) {
        AgentSession session = agentSessionService.getById(sessionId);
        if (!session.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        Agent agent = agentViewService.ownedAgent(session.getAgentId(), userId);
        ToolCallLog call = toolCallLogService.findByExternalIdAndAgentId(toolCallId, session.getAgentId())
                // The id alone addresses the agent; the session is part of the address too, so a call of
                // another conversation does not leak through this endpoint.
                .filter(log -> sessionId.toString().equals(log.getAgentSessionId()))
                .orElseThrow(() -> new NotFoundStatusException("Tool call not found"));

        McpToolCatalog.ToolEntry entry = viewEntry(viewEntries(agent), call);
        if (entry == null) {
            return SessionToolCallResponse.withNoView(call);
        }
        ViewCallResult result = call.getError() != null
                ? ViewCallResult.error(call.getError())
                : call.getFinishAt() == null
                        ? null
                        : agentViewService.viewProvider(entry).toViewResult(call.getOutput());
        return SessionToolCallResponse.of(call, entry.spec().ui().resourceUri(), result);
    }

    /**
     * Descriptors of the calls among {@code toolCallIds} this session's journal knows: one entry per
     * found call, {@code viewUri} set only when the agent's current catalog links a view. Ids without
     * a journal row are absent — a deferred or unknown tool never executes and never gets a view.
     *
     * <p>Runs without a transaction, suspending the caller's: the resolution is best-effort, and an
     * exception through a joined transactional bean would mark a shared transaction rollback-only
     * before the catch, failing the caller's commit. Suspended, each read commits or rolls back on its
     * own and a failure degrades to {@link Optional#empty()}.
     *
     * @return descriptors by tool call id; empty — the resolution failed, the caller falls back to the
     *         old per-call endpoint
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<Map<String, ToolCallDescriptor>> describe(UUID userId, UUID agentId, UUID sessionId,
                                                             Collection<String> toolCallIds) {
        if (toolCallIds.isEmpty()) {
            return Optional.of(Map.of());
        }
        try {
            Agent agent = agentViewService.ownedAgent(agentId, userId);
            Map<ViewKey, McpToolCatalog.ToolEntry> catalog = viewEntries(agent);
            Map<String, ToolCallDescriptor> descriptors = new LinkedHashMap<>();
            for (ToolCallLog call : toolCallLogService.findByAgentIdAndExternalIdIn(
                    agentId, toolCallIds.stream().distinct().toList())) {
                if (!sessionId.toString().equals(call.getAgentSessionId())) {
                    continue;
                }
                McpToolCatalog.ToolEntry entry = viewEntry(catalog, call);
                descriptors.put(call.getExternalId(), new ToolCallDescriptor(call.getExternalId(),
                        call.getName(), call.getConnectionId(),
                        entry == null ? null : entry.spec().ui().resourceUri()));
            }
            return Optional.of(descriptors);
        } catch (Exception e) {
            log.warn("view descriptors for session {} not resolved: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The agent's current views by {@code (connection, name)}. The view is resolved through the
     * catalog, not from a stored link: a revoked binding or a policy that now denies the tool leaves
     * the call without a view. Visibility {@code VIEW} is not required — the call came from the
     * model; {@code VIEW} belongs to the tools the page itself calls.
     */
    private Map<ViewKey, McpToolCatalog.ToolEntry> viewEntries(Agent agent) {
        Map<ViewKey, McpToolCatalog.ToolEntry> entries = new LinkedHashMap<>();
        for (McpToolCatalog.ToolEntry entry : agentViewService.viewCatalog(agent)) {
            if (AgentViewService.linksView(entry)) {
                entries.putIfAbsent(new ViewKey(entry.connectionId().toString(), entry.toolName()), entry);
            }
        }
        return entries;
    }

    /** The entry for the call's {@code (connection, name)}; a call outside any connection renders into nothing. */
    private static McpToolCatalog.ToolEntry viewEntry(Map<ViewKey, McpToolCatalog.ToolEntry> catalog,
                                                      ToolCallLog call) {
        if (call.getConnectionId() == null) {
            return null;
        }
        return catalog.get(new ViewKey(call.getConnectionId(), call.getName()));
    }

    private record ViewKey(String connectionId, String toolName) {
    }
}
