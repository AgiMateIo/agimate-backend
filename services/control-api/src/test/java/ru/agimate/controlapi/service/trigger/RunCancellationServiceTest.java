package ru.agimate.controlapi.service.trigger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunCancellationService")
class RunCancellationServiceTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID CHANNEL_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER = UUID.randomUUID();

    @Mock private AgentRunRepository agentRunRepository;
    @Mock private AgentSessionRepository agentSessionRepository;
    @Mock private ChannelRepository channelRepository;

    private RunCancellationService service;

    @BeforeEach
    void setUp() {
        service = new RunCancellationService(agentRunRepository, agentSessionRepository, channelRepository);
    }

    private AgentRun run(RunStatus status, UUID ownerId) {
        AgentRun run = AgentRun.builder()
                .agent(Agent.builder().id(UUID.randomUUID()).userId(ownerId).build())
                .destination("GENERIC")
                .status(status)
                .build();
        run.setId(RUN_ID);
        return run;
    }

    @Nested
    @DisplayName("cancelRun")
    class CancelRun {

        @Test
        @DisplayName("живой ран: запрос записан, статус отдаётся текущий — терминальный придёт позже")
        void recordsRequestForLiveRun() {
            when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run(RunStatus.RUNNING, USER_ID)));
            when(agentRunRepository.requestCancel(eq(RUN_ID), any())).thenReturn(1);

            RunCancellationService.CancelResult result = service.cancelRun(RUN_ID, USER_ID);

            assertTrue(result.requested());
            assertEquals(RunStatus.RUNNING, result.status());
            assertFalse(result.alreadyFinished());
        }

        @Test
        @DisplayName("ран уже закончился: не ошибка, а честный ответ «не успели»")
        void finishedRunIsNoOp() {
            when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run(RunStatus.DONE, USER_ID)));
            when(agentRunRepository.requestCancel(eq(RUN_ID), any())).thenReturn(0);

            RunCancellationService.CancelResult result = service.cancelRun(RUN_ID, USER_ID);

            assertFalse(result.requested());
            assertTrue(result.alreadyFinished());
        }

        @Test
        @DisplayName("повторное нажатие идемпотентно — второй записи не появляется")
        void repeatPressIsIdempotent() {
            when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run(RunStatus.RUNNING, USER_ID)));
            when(agentRunRepository.requestCancel(eq(RUN_ID), any())).thenReturn(0);

            RunCancellationService.CancelResult result = service.cancelRun(RUN_ID, USER_ID);

            // Ни «уже завершён», ни «записали»: ран жив, а запрос стоял до нас.
            assertFalse(result.requested());
            assertFalse(result.alreadyFinished());
        }

        @Test
        @DisplayName("чужой ран не отменяется и не раскрывается — 404, а не 403")
        void foreignRunReadsAsAbsent() {
            when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run(RunStatus.RUNNING, OTHER_USER)));

            assertThrows(NotFoundStatusException.class, () -> service.cancelRun(RUN_ID, USER_ID));
            verify(agentRunRepository, never()).requestCancel(any(), any());
        }

        @Test
        @DisplayName("несуществующий ран → 404")
        void missingRun() {
            when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundStatusException.class, () -> service.cancelRun(RUN_ID, USER_ID));
        }
    }

    @Nested
    @DisplayName("cancelSession")
    class CancelSession {

        private void stubSession(UUID ownerId) {
            AgentSession session = AgentSession.builder().channelId(CHANNEL_ID).build();
            session.setId(SESSION_ID);
            when(agentSessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
            when(channelRepository.findByIdAndDeletedAtIsNull(CHANNEL_ID))
                    .thenReturn(Optional.of(Channel.builder().id(CHANNEL_ID).userId(ownerId).build()));
        }

        @Test
        @DisplayName("гасит и работающий ран, и стоящие за ним в очереди")
        void cancelsEveryLiveRunOfTheSession() {
            stubSession(USER_ID);
            when(agentRunRepository.requestCancelBySession(eq(SESSION_ID), any())).thenReturn(3);

            assertEquals(3, service.cancelSession(SESSION_ID, USER_ID));
        }

        @Test
        @DisplayName("чужая сессия → 404, ничего не гасится")
        void foreignSessionReadsAsAbsent() {
            stubSession(OTHER_USER);

            assertThrows(NotFoundStatusException.class, () -> service.cancelSession(SESSION_ID, USER_ID));
            verify(agentRunRepository, never()).requestCancelBySession(any(), any());
        }
    }
}
