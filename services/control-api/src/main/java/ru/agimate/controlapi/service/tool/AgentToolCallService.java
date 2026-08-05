package ru.agimate.controlapi.service.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.abac.AccessDecision;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.abac.ConnectionAccessEvaluator;
import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.enums.PolicyKind;
import ru.agimate.controlapi.service.AgentService;
import ru.agimate.controlapi.service.ConnectorService;
import ru.agimate.controlapi.service.channel.InputFilterEvaluator;
import ru.agimate.controlapi.service.dto.IToolResult;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentToolCallService {

    private final AgentService agentService;
    private final ToolCallLogService toolCallLogService;
    private final ConnectionAccessEvaluator accessEvaluator;
    private final ConnectorService connectorService;

    private sealed interface EvaluationResult {
        record Created(ToolCallLog log, AccessDecision decision) implements EvaluationResult {}
        record Replay(ToolCallLog log) implements EvaluationResult {}
        record InputConflict(ToolCallLog existing) implements EvaluationResult {}
    }

    /** Idempotency check + ABAC evaluate + create log */
    private EvaluationResult evaluate(UUID agentId, ToolCallRequest request) {
        Agent agent = agentService.findById(agentId);

        var existing = toolCallLogService.findByExternalIdAndAgentId(request.getId(), agent.getId());
        if (existing.isPresent()) {
            return classifyExisting(existing.get(), request);
        }

        AccessDecision decision = accessEvaluator.evaluate(
                agent.getId(), request.getConnectionId(), PolicyKind.TOOL, request.getName());
        // params_filter constrains the call's arguments: it is permitted only if they pass the filter.
        if (decision.allowed() && decision.paramsFilter() != null
                && !InputFilterEvaluator.matches(decision.paramsFilter(), request.getInput())) {
            decision = AccessDecision.deny("Tool arguments rejected by params_filter", decision.matchedPolicyId());
        }

        try {
            ToolCallLog log = toolCallLogService.createLog(agent, request,
                    request.getAgentSessionId(), request.getRunId(), decision.accessEffect(), decision.reason());
            return new EvaluationResult.Created(log, decision);
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert with the same (agent_id, tool_call_id) — race lost.
            // Re-read and treat as replay or input conflict.
            var raced = toolCallLogService.findByExternalIdAndAgentId(request.getId(), agent.getId())
                    .orElseThrow(() -> e);
            return classifyExisting(raced, request);
        }
    }

    private EvaluationResult classifyExisting(ToolCallLog existing, ToolCallRequest request) {
        return JsonUtils.jsonEquals(existing.getInput(), request.getInput())
                ? new EvaluationResult.Replay(existing)
                : new EvaluationResult.InputConflict(existing);
    }

    /**
     * Evaluate + enforce permission + push to connector.
     *
     * <p>Call this outside an active transaction: the tool log is committed inside {@code createLog},
     * so the execution dispatch sees an already-committed row.
     */
    public String processToolCall(UUID agentId, ToolCallRequest request) {
        return switch (evaluate(agentId, request)) {
            case EvaluationResult.Replay(var log) -> {
                // A retry with the same id and input: there is no result yet — we carry the execution through to
                // the end (a crash or disconnect between committing the log and the dispatch); a rare duplicate
                // beats a loss.
                if (log.getAccessEffect() == AccessEffect.ALLOW && log.getFinishAt() == null) {
                    connectorService.pushToConnector(log);
                }
                yield log.getExternalId();
            }
            case EvaluationResult.InputConflict(var ignored) -> throw inputConflict(request.getId());
            case EvaluationResult.Created(var log, var decision) -> {
                if (!decision.allowed()) {
                    throw notAuthorized(request, decision.reason());
                }
                connectorService.pushToConnector(log);
                yield log.getExternalId();
            }
        };
    }

    /**
     * Evaluate + enforce permission, and hand the log back instead of dispatching — for a caller that
     * runs the tool itself and answers with the result (the MCP {@code tools/call}). A replay is
     * returned as-is: the caller re-executes, same as the dispatching path does.
     */
    public ToolCallLog authorizeToolCall(UUID agentId, ToolCallRequest request) {
        return switch (evaluate(agentId, request)) {
            case EvaluationResult.Replay(var log) -> log;
            case EvaluationResult.InputConflict(var ignored) -> throw inputConflict(request.getId());
            case EvaluationResult.Created(var log, var decision) -> {
                if (!decision.allowed()) {
                    throw notAuthorized(request, decision.reason());
                }
                yield log;
            }
        };
    }

    /** Evaluate permission without execution */
    public AccessEffect checkToolCall(UUID agentId, ToolCallRequest request) {
        return switch (evaluate(agentId, request)) {
            case EvaluationResult.Replay(var log) -> log.getAccessEffect();
            case EvaluationResult.InputConflict(var ignored) -> throw inputConflict(request.getId());
            case EvaluationResult.Created(var log, var decision) -> decision.accessEffect();
        };
    }

    private static ForbiddenStatusException notAuthorized(ToolCallRequest request, String reason) {
        return new ForbiddenStatusException(
                "Tool '" + request.getName() + "' is not authorized for this agent: " + reason);
    }

    private static ConflictStatusException inputConflict(String externalId) {
        return new ConflictStatusException(
                "Tool use id '" + externalId + "' was reused with a different input");
    }

    /** Get tool use log for agent */
    public ToolCallLog getToolCallLog(UUID agentId, String externalId) {
        return toolCallLogService.findByExternalIdForAgent(externalId, agentId);
    }

    /** Save tool result from agent */
    public ToolCallLog saveToolResult(UUID agentId, IToolResult toolResult) {
        return toolCallLogService.recordOutputByAgent(agentId, toolResult);
    }
}
