package ru.agimate.controlapi.service.session.compaction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.connectors.core.jobs.ConnectorJobService;
import ru.agimate.controlapi.connectors.core.jobs.JobSchedule;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.enums.ConnectorJobType;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.service.trigger.RunFinished;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides, when a conversation's run has finished, whether its upkeep is due, and if so schedules
 * the {@code sessions.compact} job. The answer to the user is already on its way: nothing here holds
 * it, and nothing here may fail the message log.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCompactionPlanner {

    /** A model call over up to ~50k tokens of transcript, with room for a slow provider. */
    static final int JOB_TIMEOUT_SECONDS = 300;

    private final AgentSessionRepository sessionRepository;
    private final ConnectionRepository connectionRepository;
    private final AgentConnectionRepository agentConnectionRepository;
    private final SessionCompactionService compactionService;
    private final ConnectorJobService jobService;

    @TransactionalEventListener
    public void onRunFinished(RunFinished finished) {
        try {
            plan(finished);
        } catch (Exception e) {
            log.error("upkeep of session {} after run {} was not planned: {}",
                    finished.sessionId(), finished.runId(), e.getMessage(), e);
        }
    }

    private void plan(RunFinished finished) {
        Optional<Connection> connection = boundConnection(finished);
        if (connection.isEmpty()) {
            return;
        }
        AgentSession session = sessionRepository.findById(finished.sessionId()).orElse(null);
        if (session == null) {
            return;
        }
        Set<SessionCompactionService.Work> due = compactionService.due(session, finished.runId());
        if (due.isEmpty()) {
            return;
        }
        JobSpec spec = new JobSpec(SessionCompactionService.COMPACT_JOB, ConnectorJobType.ONETIME,
                JobSchedule.onetimeConfig(), Map.of(), JOB_TIMEOUT_SECONDS);
        jobService.scheduleForSession(SessionCompactionService.CONNECTOR_CODE, connection.get().getId().toString(),
                finished.userId(), finished.agentId(), session.getChannelId(), session.getId(), spec);
        log.debug("upkeep {} scheduled for session {}", due, session.getId());
    }

    /** The owner's {@code sessions} connection, if this agent is bound to it — the connector is the switch. */
    private Optional<Connection> boundConnection(RunFinished finished) {
        return connectionRepository
                .findByUserIdAndConnectorCodeNotDeleted(finished.userId(), SessionCompactionService.CONNECTOR_CODE)
                .stream()
                .findFirst()
                .filter(c -> agentConnectionRepository.findActiveBinding(finished.agentId(), c.getId()).isPresent());
    }
}
