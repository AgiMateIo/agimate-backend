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

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentToolUseService {

    private final AgentService agentService;
    private final ConnectorApiService connectorApiService;
    private final ToolUseLogService toolUseLogService;
    private final ToolPolicyDbEvaluatorService toolPolicyDbEvaluatorService;

    private record EvaluationResult(ToolUseLog log, AccessDecision decision, boolean existing) {}

    /** Idempotency check + ABAC evaluate + create log */
    private EvaluationResult evaluate(UUID agentPubId, String connectorCode, ToolUseRequest request) {
        Agent agent = agentService.findByPubId(agentPubId);

        var existing = toolUseLogService.findByToolUseIdAndUserPubId(request.getId(), agent.getUserPubId());
        if (existing.isPresent()) {
            return new EvaluationResult(existing.get(), null, true);
        }

        AccessDecision decision = toolPolicyDbEvaluatorService.evaluate(
                agent.getPubId(), connectorCode, request.getIdentity(), request.getName());

        ToolUseLog log = toolUseLogService.createLog(agent, connectorCode, request.getIdentity(), request,
                request.getAgentSessionId(), decision.accessEffect(), decision.reason());

        return new EvaluationResult(log, decision, false);
    }

    /** Evaluate + enforce permission + push to connector */
    public String processToolUse(UUID agentPubId, String connectorCode, ToolUseRequest request) {
        var result = evaluate(agentPubId, connectorCode, request);
        if (result.existing()) {
            return result.log().getToolUseId();
        }

        if (!result.decision().allowed()) {
            throw new ForbiddenStatusException(
                    "Tool '" + request.getName() + "' is not authorized for this agent: " + result.decision().reason());
        }

        connectorApiService.pushToConnector(
                result.log().getUserPubId(), agentPubId.toString(), connectorCode, request.getIdentity(), request);

        return result.log().getToolUseId();
    }

    /** Evaluate permission without execution */
    public AccessEffect checkToolUse(UUID agentPubId, String connectorCode, ToolUseRequest request) {
        var result = evaluate(agentPubId, connectorCode, request);
        if (result.existing()) {
            return result.log().getAccessEffect();
        }
        return result.decision().accessEffect();
    }

    /** Save tool result from agent */
    public ToolUseLog saveToolResult(UUID agentPubId, String toolUseId, String output, String error) {
        return toolUseLogService.recordOutputByAgent(agentPubId, toolUseId, output, error);
    }
}
