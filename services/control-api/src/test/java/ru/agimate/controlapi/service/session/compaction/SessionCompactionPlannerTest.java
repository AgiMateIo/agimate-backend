package ru.agimate.controlapi.service.session.compaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.connectors.core.jobs.ConnectorJobService;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.enums.ConnectorJobType;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.service.session.compaction.SessionCompactionService.Work;
import ru.agimate.controlapi.service.trigger.RunFinished;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionCompactionPlanner — ставить ли джобу, когда ран закончился")
class SessionCompactionPlannerTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final RunFinished FINISHED = new RunFinished(RUN_ID, AGENT_ID, USER_ID, SESSION_ID);

    @Mock private AgentSessionRepository sessionRepository;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private AgentConnectionRepository agentConnectionRepository;
    @Mock private SessionCompactionService compactionService;
    @Mock private ConnectorJobService jobService;
    @InjectMocks private SessionCompactionPlanner planner;

    private AgentSession session;

    private void bound() {
        Connection connection = Connection.builder().id(CONNECTION_ID).build();
        when(connectionRepository.findByUserIdAndConnectorCodeNotDeleted(USER_ID, "sessions"))
                .thenReturn(List.of(connection));
        when(agentConnectionRepository.findActiveBinding(AGENT_ID, CONNECTION_ID))
                .thenReturn(Optional.of(AgentConnection.builder().build()));
        session = AgentSession.builder().id(SESSION_ID).channelId(UUID.randomUUID()).build();
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    }

    @Test
    @DisplayName("агент без коннектора sessions — окно режется как раньше, джобы нет")
    void notBound() {
        when(connectionRepository.findByUserIdAndConnectorCodeNotDeleted(USER_ID, "sessions")).thenReturn(List.of());

        planner.onRunFinished(FINISHED);

        verifyNoInteractions(compactionService, jobService);
    }

    @Test
    @DisplayName("делать нечего — джоба не ставится")
    void nothingDue() {
        bound();
        when(compactionService.due(session, RUN_ID)).thenReturn(Set.of());

        planner.onRunFinished(FINISHED);

        verifyNoInteractions(jobService);
    }

    @Test
    @DisplayName("пора — одна ONETIME-джоба compact на сессию, от имени агента и в его коннекции")
    void schedules() {
        bound();
        when(compactionService.due(session, RUN_ID)).thenReturn(Set.of(Work.TITLE));

        planner.onRunFinished(FINISHED);

        ArgumentCaptor<JobSpec> spec = ArgumentCaptor.forClass(JobSpec.class);
        verify(jobService).scheduleForSession(eq("sessions"), eq(CONNECTION_ID.toString()), eq(USER_ID),
                eq(AGENT_ID), eq(session.getChannelId()), eq(SESSION_ID), spec.capture());
        assertEquals("compact", spec.getValue().name());
        assertEquals(ConnectorJobType.ONETIME, spec.getValue().type());
    }

    @Test
    @DisplayName("сбой планирования не выходит наружу: ответ пользователю уже ушёл")
    void failureStaysInside() {
        when(connectionRepository.findByUserIdAndConnectorCodeNotDeleted(any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        planner.onRunFinished(FINISHED);

        verifyNoInteractions(jobService);
    }
}
