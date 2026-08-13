package ru.agimate.controlapi.service.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The session of a run that arrived without a channel: one live session per {@code (agent,
 * connection)}, so the events of one connection share a writer and a queue partition
 * (docs/decisions/agent-sessions.md).
 *
 * <p>Channel sessions are not resolved here — they belong to the route and are created by
 * {@link AgentSessionService}. The full order for a run is: the parent's session for a run born of a
 * run, then the channel's, and only then this one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSessionResolver {

    private final AgentSessionRepository agentSessionRepository;

    /**
     * The live session of this connection, created if there is none. Its own transaction: routing
     * itself runs outside one, and the unique index must not be held while the run is being enqueued
     * into DBOS.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID forConnection(UUID agentId, UUID userId, String connectorCode, UUID connectionId) {
        LocalDateTime now = LocalDateTime.now();
        AgentSession live = agentSessionRepository.findLiveConnectionSession(agentId, connectionId)
                .orElse(null);
        if (live != null) {
            agentSessionRepository.touch(live.getId(), now);
            return live.getId();
        }
        int created = agentSessionRepository.insertConnectionSession(
                agentId, userId, connectorCode, connectionId, now);
        AgentSession session = agentSessionRepository.findLiveConnectionSession(agentId, connectionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Connection session vanished right after insert: agent=" + agentId
                                + " connection=" + connectionId));
        if (created > 0) {
            log.info("Created connection session id={} for agent {} connection {}",
                    session.getId(), agentId, connectionId);
        }
        return session.getId();
    }
}
