package ru.agimate.controlapi.connectors.core.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.entities.ConnectorTask;
import ru.agimate.controlapi.database.enums.ConnectorTaskType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectorTaskScheduler")
class ConnectorTaskSchedulerTest {

    private static final long VERIFY_TIMEOUT_MS = 2_000;

    @Mock
    private ConnectorTaskService taskService;

    @Mock
    private TaskExecutionService taskExecutionService;

    @InjectMocks
    private ConnectorTaskScheduler scheduler;

    private ConnectorTask row(ConnectorTaskType type, Map<String, Object> config) {
        return ConnectorTask.builder()
                .id(UUID.randomUUID())
                .connectorCode("test")
                .identity("identity-1")
                .taskName("test.task")
                .taskType(type)
                .taskConfig(config)
                .taskArgs(Map.of())
                .timeoutSeconds(60)
                .build();
    }

    @Test
    @DisplayName("ONETIME: успех → markCompleted без следующего запуска")
    void onetimeSuccess() {
        ConnectorTask row = row(ConnectorTaskType.ONETIME, Map.of());
        when(taskService.claimReady(100)).thenReturn(List.of(row));

        scheduler.tick();

        verify(taskService, timeout(VERIFY_TIMEOUT_MS)).markCompleted(row.getId(), null);
    }

    @Test
    @DisplayName("ONETIME: ошибка → PENDING с retry через ~60s")
    void onetimeFailure() {
        ConnectorTask row = row(ConnectorTaskType.ONETIME, Map.of());
        when(taskService.claimReady(100)).thenReturn(List.of(row));
        when(taskExecutionService.executeTask(row)).thenThrow(new IllegalStateException("boom"));

        scheduler.tick();

        ArgumentCaptor<LocalDateTime> nextRun = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskService, timeout(VERIFY_TIMEOUT_MS))
                .complete(eq(row.getId()), nextRun.capture(), contains("boom"));
        assertCloseTo(LocalDateTime.now().plusSeconds(60), nextRun.getValue());
    }

    @Test
    @DisplayName("PERIODIC: успех → next_run_at = now + intervalSeconds")
    void periodicSuccess() {
        ConnectorTask row = row(ConnectorTaskType.PERIODIC, Map.of("intervalSeconds", 30));
        when(taskService.claimReady(100)).thenReturn(List.of(row));

        scheduler.tick();

        ArgumentCaptor<LocalDateTime> nextRun = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskService, timeout(VERIFY_TIMEOUT_MS))
                .complete(eq(row.getId()), nextRun.capture(), isNull());
        assertCloseTo(LocalDateTime.now().plusSeconds(30), nextRun.getValue());
    }

    @Test
    @DisplayName("PERIODIC: ошибка → retry через ~60s")
    void periodicFailure() {
        ConnectorTask row = row(ConnectorTaskType.PERIODIC, Map.of("intervalSeconds", 5));
        when(taskService.claimReady(100)).thenReturn(List.of(row));
        when(taskExecutionService.executeTask(row)).thenThrow(new IllegalStateException("fail"));

        scheduler.tick();

        ArgumentCaptor<LocalDateTime> nextRun = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskService, timeout(VERIFY_TIMEOUT_MS))
                .complete(eq(row.getId()), nextRun.capture(), contains("fail"));
        assertCloseTo(LocalDateTime.now().plusSeconds(60), nextRun.getValue());
    }

    @Test
    @DisplayName("CRON: next_run_at = следующий тик выражения")
    void cronNextTick() {
        // каждый час в 00:00 — следующий запуск на границе часа
        ConnectorTask row = row(ConnectorTaskType.CRON, Map.of("cron", "0 0 * * * *", "zone", "UTC"));
        when(taskService.claimReady(100)).thenReturn(List.of(row));

        scheduler.tick();

        ArgumentCaptor<LocalDateTime> nextRun = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskService, timeout(VERIFY_TIMEOUT_MS))
                .complete(eq(row.getId()), nextRun.capture(), isNull());
        assertEquals(0, nextRun.getValue().getMinute());
        assertEquals(0, nextRun.getValue().getSecond());
        assertTrue(nextRun.getValue().isAfter(LocalDateTime.now()));
    }

    @Test
    @DisplayName("CRON без выражения → отодвигается далеко (без busy-loop)")
    void cronWithoutExpression() {
        ConnectorTask row = row(ConnectorTaskType.CRON, Map.of());
        when(taskService.claimReady(100)).thenReturn(List.of(row));

        scheduler.tick();

        ArgumentCaptor<LocalDateTime> nextRun = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskService, timeout(VERIFY_TIMEOUT_MS))
                .complete(eq(row.getId()), nextRun.capture(), isNull());
        assertTrue(nextRun.getValue().isAfter(LocalDateTime.now().plusYears(9)));
    }

    @Test
    @DisplayName("пустой claim — ничего не исполняется")
    void emptyClaim() {
        when(taskService.claimReady(100)).thenReturn(List.of());

        scheduler.tick();

        verify(taskExecutionService, timeout(200).times(0)).executeTask(any());
    }

    private static void assertCloseTo(LocalDateTime expected, LocalDateTime actual) {
        assertTrue(Math.abs(java.time.Duration.between(expected, actual).toSeconds()) <= 5,
                "expected ~" + expected + " but was " + actual);
    }
}
