package ru.agimate.controlapi.service.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.abac.AccessDecision;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.abac.ToolPolicyDbEvaluatorService;
import ru.agimate.controlapi.controller.agent.dto.ToolUseRequest;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.service.AgentService;
import ru.agimate.controlapi.service.ConnectorService;
import ru.agimate.controlapi.service.dto.IToolResult;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentToolUseService {

    private final AgentService agentService;
    private final ToolCallLogService toolCallLogService;
    private final ToolPolicyDbEvaluatorService toolPolicyDbEvaluatorService;
    private final ConnectorService connectorService;

    private sealed interface EvaluationResult {
        record Created(ToolCallLog log, AccessDecision decision) implements EvaluationResult {}
        record Replay(ToolCallLog log) implements EvaluationResult {}
        record InputConflict(ToolCallLog existing) implements EvaluationResult {}
    }

    /** Idempotency check + ABAC evaluate + create log */
    private EvaluationResult evaluate(UUID agentId, ToolUseRequest request) {
        Agent agent = agentService.findById(agentId);

        var existing = toolCallLogService.findByExternalIdAndAgentId(request.getId(), agent.getId());
        if (existing.isPresent()) {
            return classifyExisting(existing.get(), request);
        }

        AccessDecision decision = toolPolicyDbEvaluatorService.evaluate(
                agent.getId(), request.getConnectorCode(), request.getIdentity(), request.getName());

        try {
            ToolCallLog log = toolCallLogService.createLog(agent, request,
                    request.getAgentSessionId(), decision.accessEffect(), decision.reason());
            return new EvaluationResult.Created(log, decision);
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert with the same (agent_id, tool_use_id) — race lost.
            // Re-read and treat as replay or input conflict.
            var raced = toolCallLogService.findByExternalIdAndAgentId(request.getId(), agent.getId())
                    .orElseThrow(() -> e);
            return classifyExisting(raced, request);
        }
    }

    private EvaluationResult classifyExisting(ToolCallLog existing, ToolUseRequest request) {
        return JsonUtils.jsonEquals(existing.getInput(), request.getInput())
                ? new EvaluationResult.Replay(existing)
                : new EvaluationResult.InputConflict(existing);
    }

    /** Evaluate + enforce permission + push to connector */
    public String processToolUse(UUID agentId, ToolUseRequest request) {
        return switch (evaluate(agentId, request)) {
            case EvaluationResult.Replay(var log) -> log.getExternalId();
            case EvaluationResult.InputConflict(var ignored) -> throw inputConflict(request.getId());
            case EvaluationResult.Created(var log, var decision) -> {
                if (!decision.allowed()) {
                    throw new ForbiddenStatusException(
                            "Tool '" + request.getName() + "' is not authorized for this agent: " + decision.reason());
                }
                connectorService.pushToConnector(log);
                yield log.getExternalId();
            }
        };
    }

    /** Evaluate permission without execution */
    public AccessEffect checkToolUse(UUID agentId, ToolUseRequest request) {
        return switch (evaluate(agentId, request)) {
            case EvaluationResult.Replay(var log) -> log.getAccessEffect();
            case EvaluationResult.InputConflict(var ignored) -> throw inputConflict(request.getId());
            case EvaluationResult.Created(var log, var decision) -> decision.accessEffect();
        };
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
