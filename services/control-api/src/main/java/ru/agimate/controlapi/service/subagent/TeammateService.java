package ru.agimate.controlapi.service.subagent;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.abac.AccessDecision;
import ru.agimate.controlapi.abac.ConnectionAccessEvaluator;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.enums.PolicyKind;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.service.AgentDeliveryService;
import ru.agimate.controlapi.service.channel.InputFilterEvaluator;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Who an agent may ask through {@code ask_agent} (docs/decisions/agent-to-agent-internal.md): a
 * member of its own {@code agentic_team} — the circle the board already uses — that takes part by
 * being bound to the agents connection and can be pushed to. An ABAC rule of the callee on
 * {@code request_received} is the exception layer, checked per request over the request's data.
 *
 * <p>The checks mirror {@code TriggerRouterService.findRecipients} — the same binding query, the same
 * push and ABAC tests — so a request the tool accepts is one the router delivers. Refusals are
 * {@link ConnectorException}s and name who can be asked instead.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeammateService {

    private final AgentRepository agentRepository;
    private final AgentDeliveryService agentDeliveryService;
    private final ConnectionAccessEvaluator accessEvaluator;

    /** A team member the agent may ask now. */
    public record Teammate(UUID id, String name, String description) {}

    /** Members of the asker's team that are bound to the connection, can be pushed to, and are not the asker. */
    public List<Teammate> eligible(Agent asker, UUID connectionId) {
        return candidates(asker, connectionId).stream()
                .map(a -> new Teammate(a.getId(), a.getName(), a.getDescription()))
                .toList();
    }

    /**
     * The callee of a request, or the reason there is none.
     *
     * @param requestData the request as it will travel in the trigger — the callee's
     *                    {@code params_filter} is evaluated over it
     */
    public Agent resolve(Agent asker, UUID connectionId, UUID calleeId, Map<String, Object> requestData) {
        if (asker.getAgenticTeamId() == null) {
            throw new ConnectorException("You are not in a team, so there is no one to ask; "
                    + "ask_subagent works without a team");
        }
        if (calleeId.equals(asker.getId())) {
            throw new ConnectorException("That is you; use ask_subagent to hand work to a copy of yourself");
        }
        List<Agent> candidates = candidates(asker, connectionId);
        Agent callee = candidates.stream().filter(a -> a.getId().equals(calleeId)).findFirst().orElse(null);
        if (callee == null) {
            throw new ConnectorException(whyNot(asker, connectionId, calleeId) + ". " + youCanAsk(candidates));
        }
        AccessDecision decision = accessEvaluator.evaluate(
                callee.getId(), connectionId, PolicyKind.TRIGGER, SubagentService.REQUEST_TRIGGER);
        if (!decision.allowed() || !InputFilterEvaluator.matches(decision.paramsFilter(), requestData)) {
            throw new ConnectorException(callee.getName() + " does not accept this request (a rule of that agent). "
                    + youCanAsk(candidates.stream().filter(a -> !a.getId().equals(calleeId)).toList()));
        }
        return callee;
    }

    private List<Agent> candidates(Agent asker, UUID connectionId) {
        if (asker.getAgenticTeamId() == null) {
            return List.of();
        }
        return agentRepository.findBoundToConnection(asker.getUserId(), connectionId).stream()
                .filter(a -> asker.getAgenticTeamId().equals(a.getAgenticTeamId()))
                .filter(a -> !a.getId().equals(asker.getId()))
                .filter(agentDeliveryService::supportsPush)
                .toList();
    }

    /** Which of the three conditions the agent misses — the user has to fix it, so the tool says which. */
    private String whyNot(Agent asker, UUID connectionId, UUID calleeId) {
        Agent agent = agentRepository.findById(calleeId)
                .filter(a -> a.getUserId().equals(asker.getUserId()))
                .orElse(null);
        if (agent == null) {
            return "No agent " + calleeId + " among your agents";
        }
        if (!asker.getAgenticTeamId().equals(agent.getAgenticTeamId())) {
            return agent.getName() + " is not in your team";
        }
        if (!agentDeliveryService.supportsPush(agent)) {
            return agent.getName() + " is an agent of type " + agent.getType() + " and cannot receive requests";
        }
        return agent.getName() + " has not enabled the agents skill";
    }

    private static String youCanAsk(List<Agent> candidates) {
        if (candidates.isEmpty()) {
            return "Nobody else in your team can be asked right now";
        }
        return "You can ask: " + candidates.stream()
                .map(a -> a.getName() + " (" + a.getId() + ")")
                .collect(Collectors.joining(", "));
    }
}
