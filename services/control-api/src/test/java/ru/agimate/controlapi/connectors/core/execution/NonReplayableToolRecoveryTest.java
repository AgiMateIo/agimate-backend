package ru.agimate.controlapi.connectors.core.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.service.AgentDeliveryService;
import ru.agimate.controlapi.service.tool.ToolCallLogService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NonReplayableToolRecovery")
class NonReplayableToolRecoveryTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final String CONNECTOR = "ssh";

    @Mock
    private ToolCallLogService toolCallLogService;
    @Mock
    private AgentDeliveryService agentDeliveryService;

    private NonReplayableToolRecovery recovery;

    @BeforeEach
    void setUp() {
        recovery = new NonReplayableToolRecovery(toolCallLogService, agentDeliveryService, Runnable::run);
    }

    @Test
    @DisplayName("просроченная незавершённая строка: unknown доставляет только победитель CAS")
    void recoveryDeliversOnlyCasWinner() {
        ToolCallLog expired = row("expired");
        when(toolCallLogService.findRecoverableDispatches(any(), eq(100))).thenReturn(List.of(expired));
        when(toolCallLogService.finishNonReplayable(eq(expired.getId()), any())).thenReturn(true, false);

        recovery.recover();
        recovery.recover();

        verify(agentDeliveryService, times(1)).deliverToolResult(eq(expired), argThat(result ->
                NonReplayableToolRecovery.UNKNOWN_OUTCOME_ERROR.equals(result.getError())));
        verify(toolCallLogService, times(2)).finishNonReplayable(eq(expired.getId()), any());
    }

    @Test
    @DisplayName("проигранный CAS не доставляет unknown")
    void lostCasDeliversNothing() {
        ToolCallLog expired = row("expired");
        when(toolCallLogService.findRecoverableDispatches(any(), eq(100))).thenReturn(List.of(expired));
        when(toolCallLogService.finishNonReplayable(eq(expired.getId()), any())).thenReturn(false);

        recovery.recover();

        verify(agentDeliveryService, never()).deliverToolResult(any(), any());
    }

    @Test
    @DisplayName("завершённая detached-недоставленная строка доставляется без CAS")
    void finishedDetachedUndeliveredIsRedelivered() {
        ToolCallLog finishedDetached = row("finished");
        finishedDetached.setFinishAt(LocalDateTime.now());
        finishedDetached.setDetachedAt(LocalDateTime.now());
        finishedDetached.setOutput("actual output");
        when(toolCallLogService.findRecoverableDispatches(any(), eq(100))).thenReturn(List.of(finishedDetached));

        recovery.recover();

        verify(toolCallLogService, never()).finishNonReplayable(eq(finishedDetached.getId()), any());
        verify(agentDeliveryService).deliverToolResult(eq(finishedDetached), argThat(result ->
                "actual output".equals(result.getOutput()) && result.getError() == null));
    }

    @Test
    @DisplayName("ошибка одной строки не прерывает пачку")
    void recoveryFailureDoesNotAbortBatch() {
        ToolCallLog broken = row("broken");
        ToolCallLog healthy = row("healthy");
        when(toolCallLogService.findRecoverableDispatches(any(), eq(100))).thenReturn(List.of(broken, healthy));
        when(toolCallLogService.finishNonReplayable(eq(broken.getId()), any()))
                .thenThrow(new IllegalStateException("journal is down"));
        when(toolCallLogService.finishNonReplayable(eq(healthy.getId()), any())).thenReturn(true);

        recovery.recover();

        verify(agentDeliveryService).deliverToolResult(eq(healthy), argThat(result ->
                NonReplayableToolRecovery.UNKNOWN_OUTCOME_ERROR.equals(result.getError())));
        verify(agentDeliveryService, never()).deliverToolResult(eq(broken), any());
    }

    @Test
    @DisplayName("сбой доставки одной строки не прерывает пачку")
    void deliveryFailureDoesNotAbortBatch() {
        ToolCallLog broken = row("broken");
        ToolCallLog healthy = row("healthy");
        when(toolCallLogService.findRecoverableDispatches(any(), eq(100))).thenReturn(List.of(broken, healthy));
        when(toolCallLogService.finishNonReplayable(eq(broken.getId()), any())).thenReturn(true);
        when(toolCallLogService.finishNonReplayable(eq(healthy.getId()), any())).thenReturn(true);
        doThrow(new IllegalStateException("transport is down"))
                .when(agentDeliveryService).deliverToolResult(eq(broken), any());

        recovery.recover();

        verify(agentDeliveryService).deliverToolResult(eq(healthy), any());
    }

    @Test
    @DisplayName("границей выборки служит текущее время: строка с дедлайном в будущем не выбирается")
    void futureDeadlineIsNotRecovered() {
        // The query filters on the passed now, so a future deadline simply never reaches recovery.
        when(toolCallLogService.findRecoverableDispatches(any(), eq(100))).thenReturn(List.of());
        LocalDateTime before = LocalDateTime.now();

        recovery.recover();

        LocalDateTime after = LocalDateTime.now();
        var captured = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(toolCallLogService).findRecoverableDispatches(captured.capture(), eq(100));
        assertFalse(captured.getValue().isBefore(before),
                "recovery must use the current wall clock as the deadline boundary");
        assertFalse(captured.getValue().isAfter(after),
                "recovery must not shift the deadline boundary into the future");
        verify(toolCallLogService, never()).finishNonReplayable(any(), any());
        verify(agentDeliveryService, never()).deliverToolResult(any(), any());
    }

    @Test
    @DisplayName("unknown описывает неопределённый исход дословно")
    void unknownOutcomeErrorIsExact() {
        assertEquals(
                "Execution was interrupted or its outcome is unknown; the external action may have happened",
                NonReplayableToolRecovery.UNKNOWN_OUTCOME_ERROR);
    }

    private static ToolCallLog row(String suffix) {
        LocalDateTime now = LocalDateTime.now();
        return ToolCallLog.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .agentId(AGENT_ID)
                .connectorCode(CONNECTOR)
                .connectionId(UUID.randomUUID().toString())
                .externalId("ext-" + suffix)
                .name("run_command")
                .runId(RUN_ID)
                .dispatchAt(now.minusMinutes(5))
                .dispatchDeadlineAt(now.minusMinutes(1))
                .build();
    }
}
