package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.abac.AccessDecision;
import ru.agimate.deviceapi.abac.AccessEffect;
import ru.agimate.deviceapi.abac.ToolPolicyDbEvaluatorService;
import ru.agimate.deviceapi.controller.agent.dto.ToolUseRequest;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.ToolUseLog;
import ru.agimate.deviceapi.service.dto.IToolResult;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentToolUseService {

    private final AgentService agentService;
    private final ToolUseLogService toolUseLogService;
    private final ToolPolicyDbEvaluatorService toolPolicyDbEvaluatorService;
    private final ConnectorService connectorService;

    private sealed interface EvaluationResult {
        record Created(ToolUseLog log, AccessDecision decision) implements EvaluationResult {}
        record Replay(ToolUseLog log) implements EvaluationResult {}
        record InputConflict(ToolUseLog existing) implements EvaluationResult {}
    }

    /** Idempotency check + ABAC evaluate + create log */
    private EvaluationResult evaluate(UUID agentPubId, ToolUseRequest request) {
        Agent agent = agentService.findByPubId(agentPubId);

        var existing = toolUseLogService.findByToolUseIdAndUserPubId(request.getId(), agent.getUserPubId());
        if (existing.isPresent()) {
            return JsonUtils.jsonEquals(existing.get().getInput(), request.getInput())
                    ? new EvaluationResult.Replay(existing.get())
                    : new EvaluationResult.InputConflict(existing.get());
        }

        AccessDecision decision = toolPolicyDbEvaluatorService.evaluate(
                agent.getPubId(), request.getConnectorCode(), request.getIdentity(), request.getName());

        ToolUseLog log = toolUseLogService.createLog(agent, request,
                request.getAgentSessionId(), decision.accessEffect(), decision.reason());

        return new EvaluationResult.Created(log, decision);
    }

    /** Evaluate + enforce permission + push to connector */
    public String processToolUse(UUID agentPubId, ToolUseRequest request) {
        return switch (evaluate(agentPubId, request)) {
            case EvaluationResult.Replay(var log) -> log.getToolUseId();
            case EvaluationResult.InputConflict(var ignored) -> throw inputConflict(request.getId());
            case EvaluationResult.Created(var log, var decision) -> {
                if (!decision.allowed()) {
                    throw new ForbiddenStatusException(
                            "Tool '" + request.getName() + "' is not authorized for this agent: " + decision.reason());
                }
                connectorService.pushToConnector(log);
                yield log.getToolUseId();
            }
        };
    }

    /** Evaluate permission without execution */
    public AccessEffect checkToolUse(UUID agentPubId, ToolUseRequest request) {
        return switch (evaluate(agentPubId, request)) {
            case EvaluationResult.Replay(var log) -> log.getAccessEffect();
            case EvaluationResult.InputConflict(var ignored) -> throw inputConflict(request.getId());
            case EvaluationResult.Created(var log, var decision) -> decision.accessEffect();
        };
    }

    private static ConflictStatusException inputConflict(String toolUseId) {
        return new ConflictStatusException(
                "Tool use id '" + toolUseId + "' was reused with a different input");
    }

    /** Get tool use log for agent */
    public ToolUseLog getToolUseLog(UUID agentPubId, String toolUseId) {
        return toolUseLogService.findByToolUseIdForAgent(toolUseId, agentPubId);
    }

    /** Save tool result from agent */
    public ToolUseLog saveToolResult(UUID agentPubId, IToolResult toolResult) {
        return toolUseLogService.recordOutputByAgent(agentPubId, toolResult);
    }
}
