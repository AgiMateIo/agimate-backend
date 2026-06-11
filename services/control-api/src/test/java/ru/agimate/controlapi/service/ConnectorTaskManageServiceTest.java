package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.ConnectorTask;
import ru.agimate.controlapi.database.enums.ConnectorTaskKind;
import ru.agimate.controlapi.database.enums.ConnectorTaskStatus;
import ru.agimate.controlapi.database.enums.ConnectorTaskType;
import ru.agimate.controlapi.database.repositories.ConnectorTaskRepository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectorTaskManageService")
class ConnectorTaskManageServiceTest {

    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();

    @Mock
    private ConnectorTaskRepository repository;

    @InjectMocks
    private ConnectorTaskManageService service;

    private ConnectorTask task(ConnectorTaskKind kind, ConnectorTaskType type, Map<String, Object> config) {
        return ConnectorTask.builder()
                .id(TASK_ID)
                .connectorCode("time")
                .userId(USER_ID)
                .kind(kind)
                .taskName("time.fire")
                .taskType(type)
                .taskConfig(config)
                .taskArgs(Map.of("prompt", "test"))
                .status(ConnectorTaskStatus.PENDING)
                .nextRunAt(LocalDateTime.now().plusHours(1))
                .timeoutSeconds(60)
                .build();
    }

    @Nested
    @DisplayName("pause")
    class Pause {

        @Test
        @DisplayName("ставит paused_at точечным UPDATE")
        void pausesTask() {
            ConnectorTask task = task(ConnectorTaskKind.AGENT, ConnectorTaskType.PERIODIC,
                    Map.of("intervalSeconds", 300));
            when(repository.findById(TASK_ID)).thenReturn(Optional.of(task));

            service.pause(TASK_ID, USER_ID);

            verify(repository).pause(eq(TASK_ID), eq(USER_ID), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("несуществующая задача → 404")
        void missingTaskThrowsNotFound() {
            when(repository.findById(TASK_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundStatusException.class, () -> service.pause(TASK_ID, USER_ID));
        }

        @Test
        @DisplayName("чужая задача → 404, существование не раскрывается")
        void foreignTaskThrowsNotFound() {
            ConnectorTask task = task(ConnectorTaskKind.AGENT, ConnectorTaskType.PERIODIC, Map.of());
            when(repository.findById(TASK_ID)).thenReturn(Optional.of(task));

            assertThrows(NotFoundStatusException.class, () -> service.pause(TASK_ID, OTHER_USER_ID));
            verify(repository, never()).pause(any(), any(), any());
        }

        @Test
        @DisplayName("COMPLETED → 400")
        void completedTaskThrowsBadRequest() {
            ConnectorTask task = task(ConnectorTaskKind.AGENT, ConnectorTaskType.ONETIME, Map.of());
            task.setStatus(ConnectorTaskStatus.COMPLETED);
            when(repository.findById(TASK_ID)).thenReturn(Optional.of(task));

            assertThrows(BadRequestStatusException.class, () -> service.pause(TASK_ID, USER_ID));
        }
    }

    @Nested
    @DisplayName("resume")
    class Resume {

        @Test
        @DisplayName("не на паузе → no-op")
        void notPausedIsNoop() {
            ConnectorTask task = task(ConnectorTaskKind.AGENT, ConnectorTaskType.PERIODIC,
                    Map.of("intervalSeconds", 300));
            when(repository.findById(TASK_ID)).thenReturn(Optional.of(task));

            service.resume(TASK_ID, USER_ID);

            verify(repository, never()).resume(any(), any(), any());
        }

        @Test
        @DisplayName("PERIODIC: next_run_at = now + intervalSeconds, без догоняющего запуска")
        void periodicRecomputesFromNow() {
            ConnectorTask task = task(ConnectorTaskKind.AGENT, ConnectorTaskType.PERIODIC,
                    Map.of("intervalSeconds", 300));
            task.setPausedAt(LocalDateTime.now().minusDays(1));
            task.setNextRunAt(LocalDateTime.now().minusDays(1));
            when(repository.findById(TASK_ID)).thenReturn(Optional.of(task));

            service.resume(TASK_ID, USER_ID);

            ArgumentCaptor<LocalDateTime> nextRun = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(repository).resume(eq(TASK_ID), eq(USER_ID), nextRun.capture());
            LocalDateTime expected = LocalDateTime.now().plusSeconds(300);
            assertTrue(Math.abs(java.time.Duration.between(expected, nextRun.getValue()).getSeconds()) <= 2,
                    "next_run_at должен быть ~now+300s, получено: " + nextRun.getValue());
        }

        @Test
        @DisplayName("CRON: next_run_at = следующий тик выражения от now")
        void cronRecomputesNextTick() {
            ConnectorTask task = task(ConnectorTaskKind.AGENT, ConnectorTaskType.CRON,
                    Map.of("cron", "0 0 9 * * *", "zone", "UTC"));
            task.setPausedAt(LocalDateTime.now().minusDays(3));
            when(repository.findById(TASK_ID)).thenReturn(Optional.of(task));

            service.resume(TASK_ID, USER_ID);

            ArgumentCaptor<LocalDateTime> nextRun = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(repository).resume(eq(TASK_ID), eq(USER_ID), nextRun.capture());
            assertEquals(9, nextRun.getValue().getHour());
            assertEquals(0, nextRun.getValue().getMinute());
            assertTrue(nextRun.getValue().isAfter(LocalDateTime.now()));
        }

        @Test
        @DisplayName("ONETIME: next_run_at сохраняется как был")
        void onetimeKeepsNextRunAt() {
            ConnectorTask task = task(ConnectorTaskKind.AGENT, ConnectorTaskType.ONETIME, Map.of());
            LocalDateTime original = task.getNextRunAt();
            task.setPausedAt(LocalDateTime.now().minusHours(2));
            when(repository.findById(TASK_ID)).thenReturn(Optional.of(task));

            service.resume(TASK_ID, USER_ID);

            verify(repository).resume(TASK_ID, USER_ID, original);
        }

        @Test
        @DisplayName("COMPLETED → 400")
        void completedTaskThrowsBadRequest() {
            ConnectorTask task = task(ConnectorTaskKind.AGENT, ConnectorTaskType.ONETIME, Map.of());
            task.setStatus(ConnectorTaskStatus.COMPLETED);
            when(repository.findById(TASK_ID)).thenReturn(Optional.of(task));

            assertThrows(BadRequestStatusException.class, () -> service.resume(TASK_ID, USER_ID));
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("AGENT-задача удаляется")
        void deletesAgentTask() {
            ConnectorTask task = task(ConnectorTaskKind.AGENT, ConnectorTaskType.PERIODIC, Map.of());
            when(repository.findById(TASK_ID)).thenReturn(Optional.of(task));

            service.delete(TASK_ID, USER_ID);

            verify(repository).delete(task);
        }

        @Test
        @DisplayName("SYSTEM-задача → 400: управляется reconcile-синком")
        void systemTaskThrowsBadRequest() {
            ConnectorTask task = task(ConnectorTaskKind.SYSTEM, ConnectorTaskType.PERIODIC, Map.of());
            when(repository.findById(TASK_ID)).thenReturn(Optional.of(task));

            assertThrows(BadRequestStatusException.class, () -> service.delete(TASK_ID, USER_ID));
            verify(repository, never()).delete(any(ConnectorTask.class));
        }

        @Test
        @DisplayName("чужая задача → 404")
        void foreignTaskThrowsNotFound() {
            ConnectorTask task = task(ConnectorTaskKind.AGENT, ConnectorTaskType.PERIODIC, Map.of());
            when(repository.findById(TASK_ID)).thenReturn(Optional.of(task));

            assertThrows(NotFoundStatusException.class, () -> service.delete(TASK_ID, OTHER_USER_ID));
            verify(repository, never()).delete(any(ConnectorTask.class));
        }
    }
}
