package ru.agimate.controlapi.service.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;
import ru.agimate.controlapi.service.channel.handler.WebchatChannelHandler;
import ru.agimate.controlapi.service.webchat.WebchatService;

import java.util.Map;

/**
 * Live changes of the user's conversations on their own channel (docs/decisions/session-events.md).
 * The payload is the listing row, built at the moment of the event: absolute values survive the
 * at-least-once delivery, and the client replaces the row whatever it was that changed.
 *
 * <p>A webchat session also moves its agent's contact row — the unread sum across all of the agent's
 * conversations, which the client cannot add up from one session's row.
 *
 * <p>After the commit, because the row is read back from the database. A failure is swallowed: a
 * lost event is a stale screen until the next read, while an exception would fail what was done.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionEventPublisher {

    public static final String CREATED = "session.created";
    public static final String UPDATED = "session.updated";
    public static final String AGENT_UPDATED = "webchat.agent.updated";

    static final String ENTITY = "session";
    static final String AGENT_ENTITY = "webchat.agent";

    private final AgentSessionRepository agentSessionRepository;
    private final ManageSessionService manageSessionService;
    private final WebchatService webchatService;
    private final CentrifugoService centrifugoService;

    // A transaction of its own: after the commit the finished one's persistence context is still
    // bound, and a session it had loaded before a bulk update would come back with the old values.
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSessionChanged(SessionChanged event) {
        try {
            AgentSession session = agentSessionRepository.findById(event.sessionId()).orElse(null);
            if (session == null) {
                return;
            }
            String channel = "user:" + session.getUserId();
            String agentId = session.getAgentId().toString();
            centrifugoService.publishMessage(channel,
                    event.created() ? CREATED : UPDATED,
                    manageSessionService.row(session),
                    Map.of("entity", ENTITY, "agentId", agentId));
            if (WebchatChannelHandler.CONNECTOR_CODE.equals(session.getConnectorCode())) {
                webchatService.contact(session.getAgentId()).ifPresent(contact ->
                        centrifugoService.publishMessage(channel, AGENT_UPDATED, contact,
                                Map.of("entity", AGENT_ENTITY, "agentId", agentId)));
            }
        } catch (Exception e) {
            log.warn("Failed to publish change of session {}: {}", event.sessionId(), e.getMessage());
        }
    }
}
