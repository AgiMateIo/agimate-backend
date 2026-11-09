package ru.agimate.controlapi.service.view;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.dto.ToolAudience;
import ru.agimate.controlapi.connectors.core.dto.ToolUi;
import ru.agimate.controlapi.connectors.core.dto.ViewCallResult;
import ru.agimate.controlapi.controller.manage.dto.session.SessionToolCallResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.service.mcp.McpToolCatalog;
import ru.agimate.controlapi.service.session.AgentSessionService;
import ru.agimate.controlapi.service.tool.ToolCallLogService;

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

        McpToolCatalog.ToolEntry entry = viewEntry(agent, call);
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
     * The view is resolved through the current catalog, not from a stored link: a revoked binding, a
     * policy that now denies the tool, or a tool hidden from views leaves the call without a view.
     */
    private McpToolCatalog.ToolEntry viewEntry(Agent agent, ToolCallLog call) {
        if (call.getConnectionId() == null) {
            return null;
        }
        return agentViewService.viewCatalog(agent).stream()
                .filter(AgentViewService::linksView)
                .filter(entry -> ToolUi.visibleTo(entry.spec().ui(), ToolAudience.VIEW))
                .filter(entry -> entry.connectionId().toString().equals(call.getConnectionId()))
                .filter(entry -> entry.toolName().equals(call.getName()))
                .findFirst()
                .orElse(null);
    }
}
