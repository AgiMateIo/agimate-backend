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
import ru.agimate.controlapi.service.team.TeamCircleService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Who an agent may ask through {@code ask_agent} (docs/decisions/agent-to-agent-internal.md): a
 * participant of the agents connector in its own team — the circle {@link TeamCircleService}
 * computes for every team connector — other than itself. An ABAC rule of the callee on
 * {@code request_received} is the exception layer, checked per request over the request's data.
 *
 * <p>Refusals are {@link ConnectorException}s and name who can be asked instead.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeammateService {

    private final TeamCircleService teamCircleService;
    private final AgentRepository agentRepository;
    private final AgentDeliveryService agentDeliveryService;
    private final ConnectionAccessEvaluator accessEvaluator;

    /** A team member the agent may ask now. */
    public record Teammate(UUID id, String name, String description) {}

    /** Participants of the agents connector in the asker's team, other than the asker. */
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
            throw new ConnectorException(whyNot(asker, calleeId) + ". " + youCanAsk(candidates));
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
        return teamCircleService.participants(asker.getUserId(), asker.getAgenticTeamId(), connectionId).stream()
                .filter(a -> !a.getId().equals(asker.getId()))
                .toList();
    }

    /** Which of the three conditions the agent misses — the user has to fix it, so the tool says which. */
    private String whyNot(Agent asker, UUID calleeId) {
        Agent agent = agentRepository.findById(calleeId)
                .filter(a -> a.getUserId().equals(asker.getUserId()))
                .orElse(null);
        if (agent == null) {
            return "No agent " + calleeId + " among your agents";
        }
        if (!teamCircleService.isMember(asker.getAgenticTeamId(), agent)) {
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
