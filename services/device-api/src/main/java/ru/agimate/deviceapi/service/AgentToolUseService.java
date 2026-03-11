package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.ForbiddenStatusException;
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
    private final AppApiService appApiService;
    private final ToolUseLogService toolUseLogService;
    private final ToolPolicyDbEvaluatorService toolPolicyDbEvaluatorService;

    private record EvaluationResult(ToolUseLog log, AccessDecision decision, boolean existing) {}

    /** Idempotency check + ABAC evaluate + create log */
    private EvaluationResult evaluate(UUID agentPubId, ToolUseRequest request) {
        Agent agent = agentService.findByPubId(agentPubId);

        var existing = toolUseLogService.findByToolUseIdAndUserPubId(request.getId(), agent.getUserPubId());
        if (existing.isPresent()) {
            return new EvaluationResult(existing.get(), null, true);
        }

        AccessDecision decision = toolPolicyDbEvaluatorService.evaluate(
                agent.getPubId(), request.getConnectorCode(), request.getIdentity(), request.getName());

        ToolUseLog log = toolUseLogService.createLog(agent, request,
                request.getAgentSessionId(), decision.accessEffect(), decision.reason());

        return new EvaluationResult(log, decision, false);
    }

    /** Evaluate + enforce permission + push to connector */
    public String processToolUse(UUID agentPubId, ToolUseRequest toolUseRequest) {
        var result = evaluate(agentPubId, toolUseRequest);
        if (result.existing()) {
            return result.log().getToolUseId();
        }

        if (!result.decision().allowed()) {
            throw new ForbiddenStatusException(
                    "Tool '" + toolUseRequest.getName() + "' is not authorized for this agent: " + result.decision().reason());
        }

        appApiService.pushToConnector(
                result.log().getUserPubId(), agentPubId.toString(), toolUseRequest);

        return result.log().getToolUseId();
    }

    /** Evaluate permission without execution */
    public AccessEffect checkToolUse(UUID agentPubId, ToolUseRequest request) {
        var result = evaluate(agentPubId, request);
        if (result.existing()) {
            return result.log().getAccessEffect();
        }
        return result.decision().accessEffect();
    }

    /** Save tool result from agent */
    public ToolUseLog saveToolResult(UUID agentPubId, IToolResult toolResult) {
        return toolUseLogService.recordOutputByAgent(agentPubId, toolResult);
    }
}
