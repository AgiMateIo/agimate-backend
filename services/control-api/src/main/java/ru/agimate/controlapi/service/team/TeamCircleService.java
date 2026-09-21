package ru.agimate.controlapi.service.team;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.service.AgentDeliveryService;

import java.util.List;
import java.util.UUID;

/**
 * The circle of an agentic team, as the team connectors see it — the one thing the board and the
 * agents connector share. Each connector is independent: a team may use either, both or neither,
 * and taking part in one is that connector's own binding. What they agree on is who is in the
 * circle at all, and that is decided here rather than once per connector.
 *
 * <p>{@link #roster} is the membership — the broadcast addressee of a board event, the pool a request
 * may go to. {@link #participants} narrows it the way {@code TriggerRouterService.findRecipients}
 * will: bound to the connector's connection and reachable by push. A caller that checks a callee
 * against this list therefore refuses exactly what the router would drop.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamCircleService {

    private final AgentRepository agentRepository;
    private final AgentDeliveryService agentDeliveryService;

    /** Whether the agent is in the team; a team-less agent is in no circle. */
    public boolean isMember(UUID teamId, Agent agent) {
        return teamId != null && teamId.equals(agent.getAgenticTeamId());
    }

    /** Every agent of the user in the team. */
    public List<Agent> roster(UUID userId, UUID teamId) {
        return agentRepository.findByUserIdAndAgenticTeamId(userId, teamId);
    }

    /**
     * Team members that take part in the connector behind {@code connectionId}: bound to it and
     * pushable. The binding query is the router's own, so the two views cannot drift apart.
     */
    public List<Agent> participants(UUID userId, UUID teamId, UUID connectionId) {
        if (teamId == null) {
            return List.of();
        }
        return agentRepository.findBoundToConnection(userId, connectionId).stream()
                .filter(agent -> isMember(teamId, agent))
                .filter(agentDeliveryService::supportsPush)
                .toList();
    }
}
