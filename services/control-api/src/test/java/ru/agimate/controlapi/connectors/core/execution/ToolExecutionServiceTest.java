package ru.agimate.controlapi.connectors.core.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvFactory;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.connectors.core.ToolExecutionPolicy;
import ru.agimate.controlapi.connectors.core.ToolProvider;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.CredentialField;
import ru.agimate.controlapi.connectors.core.dto.IntegrationValidationResult;
import ru.agimate.controlapi.connectors.core.execution.ToolExecutionService.WaitOutcome;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.service.AgentDeliveryService;
import ru.agimate.controlapi.service.dto.IToolResult;
import ru.agimate.controlapi.service.dto.ToolResult;
import ru.agimate.controlapi.service.tool.ToolCallLogService;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ToolExecutionService")
class ToolExecutionServiceTest {

    private static final UUID LOG_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final String CONNECTOR = "fake";
    private static final String TOOL = "run_command";
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    @Mock
    private ConnectorRegistry connectorRegistry;
    @Mock
    private ConnectionRepository connectionRepository;
    @Mock
    private AgentRunRepository agentRunRepository;
    @Mock
    private AgentSessionRepository agentSessionRepository;
    @Mock
    private ConnectorEnvFactory envFactory;
    @Mock
    private ToolCallLogService toolCallLogService;
    @Mock
    private AgentDeliveryService agentDeliveryService;
    @Mock
    private AsyncTaskExecutor toolExecutor;

    @InjectMocks
    private ToolExecutionService toolExecutionService;

    private FakeConnector connector;

    @Nested
    @DisplayName("REPLAYABLE")
    class ReplayablePath {

        @Test
        @DisplayName("идёт прежним путём: отмена до env, затем исполнение и запись результата")
        void replayableStillUsesRecordOutput() {
            connector = connector(ToolExecutionPolicy.replayable());
            stubConnection();
            when(agentRunRepository.isCancelRequested(RUN_ID)).thenReturn(false);
            ToolCallLog log = log();

            toolExecutionService.executeTool(log);

            assertEquals(1, connector.executions.get());
            verify(toolCallLogService, never()).claimDispatch(any(), any());
            verify(toolCallLogService, never()).isUnfinished(any());
            InOrder order = inOrder(agentRunRepository, connectionRepository, toolCallLogService);
            order.verify(agentRunRepository).isCancelRequested(RUN_ID);
            order.verify(connectionRepository).findByIdAndUserIdNotDeleted(CONNECTION_ID, USER_ID);
            order.verify(toolCallLogService).recordOutput(eq(LOG_ID), resultWithOutput());
            verify(connectionRepository).updateLastUsedAt(eq(CONNECTION_ID), any());
            verify(toolCallLogService, never()).finishNonReplayable(any(), any());
            verify(agentDeliveryService).deliverToolResult(eq(log), any());
        }

        @Test
        @DisplayName("сбой журналирования не отменяет доставку replayable-результата")
        void replayablePersistenceFailureStillDelivers() {
            connector = connector(ToolExecutionPolicy.replayable());
            stubConnection();
            when(agentRunRepository.isCancelRequested(RUN_ID)).thenReturn(false);
            doThrow(new IllegalStateException("journal is down"))
                    .when(toolCallLogService).recordOutput(eq(LOG_ID), any());
            ToolCallLog log = log();

            toolExecutionService.executeTool(log);

            assertEquals(1, connector.executions.get());
            verify(agentDeliveryService).deliverToolResult(eq(log), resultWithOutput());
        }

        @Test
        @DisplayName("отменённый replay не открывает соединение и не вызывает провайдера")
        void cancelledReplayDoesNotExecute() {
            connector = connector(ToolExecutionPolicy.replayable());
            when(agentRunRepository.isCancelRequested(RUN_ID)).thenReturn(true);
            ToolCallLog log = log();

            toolExecutionService.executeTool(log);

            assertEquals(0, connector.executions.get());
            verifyNoInteractions(envFactory);
            verify(connectionRepository, never()).findByIdAndUserIdNotDeleted(any(), any());
            verify(toolCallLogService).recordOutput(eq(LOG_ID),
                    argThat(result -> result.getError() != null && result.getError().startsWith("Cancelled")));
            verify(toolCallLogService, never()).claimDispatch(any(), any());
        }

        @Test
        @DisplayName("сбой провайдера пишется напрямую, без чтения dispatch-состояния")
        void replayableFailureRecordsWithoutDispatchRead() {
            connector = failingConnector(new ConnectorException("command rejected"));
            stubConnection();
            ToolCallLog log = log();

            toolExecutionService.executeTool(log);

            verify(toolCallLogService).recordOutput(eq(LOG_ID), errorWith("command rejected"));
            verify(toolCallLogService, never()).isDispatched(any());
            verify(toolCallLogService, never()).finishNonReplayable(any(), any());
            verify(agentDeliveryService).deliverToolResult(eq(log), errorWith("command rejected"));
        }

        @Test
        @DisplayName("сбой провайдера доставляется, даже если чтение dispatch-состояния упало бы")
        void replayableFailureIgnoresDispatchReadFailure() {
            connector = failingConnector(new ConnectorException("command rejected"));
            stubConnection();
            lenient().when(toolCallLogService.isDispatched(LOG_ID))
                    .thenThrow(new IllegalStateException("db is down"));
            ToolCallLog log = log();

            toolExecutionService.executeTool(log);

            verify(toolCallLogService).recordOutput(eq(LOG_ID), errorWith("command rejected"));
            verify(agentDeliveryService).deliverToolResult(eq(log), errorWith("command rejected"));
        }
    }

    @Nested
    @DisplayName("NON_REPLAYABLE")
    class NonReplayablePath {

        @Test
        @DisplayName("выигранный claim: isUnfinished, env, провайдер, CAS — ровно один раз")
        void winningClaimExecutesOnce() {
            connector = connector(ToolExecutionPolicy.nonReplayable(TIMEOUT));
            stubConnection();
            when(toolCallLogService.claimDispatch(LOG_ID, TIMEOUT)).thenReturn(true);
            when(toolCallLogService.isUnfinished(LOG_ID)).thenReturn(true);
            when(toolCallLogService.finishNonReplayable(eq(LOG_ID), any())).thenReturn(true);
            ToolCallLog log = log();

            toolExecutionService.executeTool(log);

            assertEquals(1, connector.executions.get());
            InOrder order = inOrder(toolCallLogService, agentRunRepository, connectionRepository);
            order.verify(toolCallLogService).claimDispatch(LOG_ID, TIMEOUT);
            order.verify(agentRunRepository).isCancelRequested(RUN_ID);
            order.verify(toolCallLogService).isUnfinished(LOG_ID);
            order.verify(connectionRepository).findByIdAndUserIdNotDeleted(CONNECTION_ID, USER_ID);
            order.verify(connectionRepository).updateLastUsedAt(eq(CONNECTION_ID), any());
            order.verify(toolCallLogService).finishNonReplayable(eq(LOG_ID), resultWithOutput());
            verify(toolCallLogService, never()).recordOutput(any(), any());
            verify(agentDeliveryService).deliverToolResult(eq(log), resultWithOutput());
        }

        @Test
        @DisplayName("проигранный claim не доходит до провайдера и ничего не пишет")
        void nonReplayableClaimLostDoesNotInvokeConnector() {
            connector = connector(ToolExecutionPolicy.nonReplayable(TIMEOUT));
            when(toolCallLogService.claimDispatch(LOG_ID, TIMEOUT)).thenReturn(false);
            ToolCallLog log = log();

            toolExecutionService.executeTool(log);

            assertEquals(0, connector.executions.get());
            verify(toolCallLogService, never()).recordOutput(any(), any());
            verify(toolCallLogService, never()).isUnfinished(any());
            verify(agentRunRepository, never()).isCancelRequested(any());
            verify(agentDeliveryService, never()).deliverToolResult(any(), any());
            verifyNoInteractions(envFactory);
        }

        @Test
        @DisplayName("проигранный claim не доходит до env — живая строка не затирается")
        void lostClaimDoesNotReachEnv() {
            // The connection lookup would fail (no connection row) — a duplicate dispatch must not
            // even get there, let alone record the failure over the row the first attempt is running.
            connector = connector(ToolExecutionPolicy.nonReplayable(TIMEOUT));
            when(toolCallLogService.claimDispatch(LOG_ID, TIMEOUT)).thenReturn(false);
            ToolCallLog log = log();

            toolExecutionService.executeTool(log);

            assertEquals(0, connector.executions.get());
            verify(connectionRepository, never()).findByIdAndUserIdNotDeleted(any(), any());
            verifyNoInteractions(envFactory);
            verify(toolCallLogService, never()).recordOutput(any(), any());
            verify(agentDeliveryService, never()).deliverToolResult(any(), any());
        }

        @Test
        @DisplayName("завершённая между claim и запуском строка не запускает команду")
        void finishedAfterClaimDoesNotStart() {
            connector = connector(ToolExecutionPolicy.nonReplayable(TIMEOUT));
            when(toolCallLogService.claimDispatch(LOG_ID, TIMEOUT)).thenReturn(true);
            when(toolCallLogService.isUnfinished(LOG_ID)).thenReturn(false);
            ToolCallLog log = log();

            toolExecutionService.executeTool(log);

            assertEquals(0, connector.executions.get());
            verifyNoInteractions(envFactory);
            verify(toolCallLogService, never()).recordOutput(any(), any());
            verify(agentDeliveryService, never()).deliverToolResult(any(), any());
        }

        @Test
        @DisplayName("отмена после claim финализируется через CAS и не открывает соединение")
        void cancelledClaimedCallUsesCas() {
            connector = connector(ToolExecutionPolicy.nonReplayable(TIMEOUT));
            when(toolCallLogService.claimDispatch(LOG_ID, TIMEOUT)).thenReturn(true);
            when(agentRunRepository.isCancelRequested(RUN_ID)).thenReturn(true);
            when(toolCallLogService.finishNonReplayable(eq(LOG_ID), any())).thenReturn(true);
            ToolCallLog log = log();

            toolExecutionService.executeTool(log);

            assertEquals(0, connector.executions.get());
            InOrder order = inOrder(toolCallLogService, agentRunRepository);
            order.verify(toolCallLogService).claimDispatch(LOG_ID, TIMEOUT);
            order.verify(agentRunRepository).isCancelRequested(RUN_ID);
            verify(toolCallLogService, never()).isUnfinished(any());
            verifyNoInteractions(envFactory);
            verify(connectionRepository, never()).findByIdAndUserIdNotDeleted(any(), any());
            verify(toolCallLogService).finishNonReplayable(eq(LOG_ID), cancelledResult());
            verify(toolCallLogService, never()).recordOutput(any(), any());
            verify(agentDeliveryService).deliverToolResult(eq(log), cancelledResult());
        }

        @Test
        @DisplayName("отмена при уже захваченной строке не пишет и не доставляет")
        void cancelledReplayAfterClaimWritesNothing() {
            connector = connector(ToolExecutionPolicy.nonReplayable(TIMEOUT));
            // The run is cancelled in the world, but the lost claim ends the attempt before any check.
            lenient().when(agentRunRepository.isCancelRequested(RUN_ID)).thenReturn(true);
            when(toolCallLogService.claimDispatch(LOG_ID, TIMEOUT)).thenReturn(false);
            ToolCallLog log = log();

            toolExecutionService.executeTool(log);

            assertEquals(0, connector.executions.get());
            verify(agentRunRepository, never()).isCancelRequested(any());
            verify(toolCallLogService, never()).recordOutput(any(), any());
            verify(toolCallLogService, never()).finishNonReplayable(any(), any());
            verify(toolCallLogService, never()).isUnfinished(any());
            verifyNoInteractions(envFactory);
            verify(agentDeliveryService, never()).deliverToolResult(any(), any());
        }

        @Test
        @DisplayName("параллельные вызовы: провайдер исполняется только у выигравшего claim")
        void parallelReplayClaimsOnlyOnce() throws Exception {
            connector = connector(ToolExecutionPolicy.nonReplayable(TIMEOUT));
            stubConnection();
            AtomicBoolean claimed = new AtomicBoolean();
            when(toolCallLogService.claimDispatch(eq(LOG_ID), eq(TIMEOUT)))
                    .thenAnswer(invocation -> claimed.compareAndSet(false, true));
            when(toolCallLogService.isUnfinished(LOG_ID)).thenReturn(true);
            when(toolCallLogService.finishNonReplayable(eq(LOG_ID), any())).thenReturn(true);
            ToolCallLog log = log();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                Callable<Void> attempt = () -> {
                    start.await();
                    toolExecutionService.executeTool(log);
                    return null;
                };
                Future<?> first = pool.submit(attempt);
                Future<?> second = pool.submit(attempt);
                start.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            assertEquals(1, connector.executions.get());
            verify(toolCallLogService, times(1)).finishNonReplayable(eq(LOG_ID), any());
            verify(agentDeliveryService, times(1)).deliverToolResult(eq(log), any());
        }

        @Test
        @DisplayName("поздний фактический результат не перезаписывает unknown и не доставляется")
        void lateActualResultDoesNotOverwriteUnknown() {
            connector = connector(ToolExecutionPolicy.nonReplayable(TIMEOUT));
            stubConnection();
            when(toolCallLogService.claimDispatch(LOG_ID, TIMEOUT)).thenReturn(true);
            when(toolCallLogService.isUnfinished(LOG_ID)).thenReturn(true);
            // Recovery finalized the row as unknown while the command was still running.
            when(toolCallLogService.finishNonReplayable(eq(LOG_ID), any())).thenReturn(false);
            ToolCallLog log = log();

            toolExecutionService.executeTool(log);

            assertEquals(1, connector.executions.get());
            verify(toolCallLogService).finishNonReplayable(eq(LOG_ID), resultWithOutput());
            verify(toolCallLogService, never()).recordOutput(any(), any());
            verify(agentDeliveryService, never()).deliverToolResult(any(), any());
        }

        @Test
        @DisplayName("сбой CAS-записи не доставляет исход")
        void nonReplayablePersistenceFailureSuppressesDelivery() {
            connector = connector(ToolExecutionPolicy.nonReplayable(TIMEOUT));
            stubConnection();
            when(toolCallLogService.claimDispatch(LOG_ID, TIMEOUT)).thenReturn(true);
            when(toolCallLogService.isUnfinished(LOG_ID)).thenReturn(true);
            when(toolCallLogService.finishNonReplayable(eq(LOG_ID), any()))
                    .thenThrow(new IllegalStateException("journal is down"));
            ToolCallLog log = log();

            toolExecutionService.executeTool(log);

            assertEquals(1, connector.executions.get());
            verify(toolCallLogService, never()).recordOutput(any(), any());
            verify(agentDeliveryService, never()).deliverToolResult(any(), any());
        }

        @Test
        @DisplayName("сбой last-used не превращает успешную команду в ошибку")
        void lastUsedFailureKeepsSuccessfulSshResult() {
            connector = connector(ToolExecutionPolicy.nonReplayable(TIMEOUT));
            stubConnection();
            when(toolCallLogService.claimDispatch(LOG_ID, TIMEOUT)).thenReturn(true);
            when(toolCallLogService.isUnfinished(LOG_ID)).thenReturn(true);
            when(toolCallLogService.finishNonReplayable(eq(LOG_ID), any())).thenReturn(true);
            doThrow(new IllegalStateException("connection row is gone"))
                    .when(connectionRepository).updateLastUsedAt(eq(CONNECTION_ID), any());
            ToolCallLog log = log();

            toolExecutionService.executeTool(log);

            verify(toolCallLogService).finishNonReplayable(eq(LOG_ID), resultWithOutput());
            verify(agentDeliveryService).deliverToolResult(eq(log), resultWithOutput());
        }

        @Test
        @DisplayName("поздний результат не доставляется повторно после unknown от recovery")
        void lateResultCannotBeRedeliveredAfterUnknown() {
            connector = connector(ToolExecutionPolicy.nonReplayable(TIMEOUT));
            // dispatch_at is already stamped: recovery declared the call unknown and delivered it.
            when(toolCallLogService.claimDispatch(LOG_ID, TIMEOUT)).thenReturn(false);
            ToolCallLog log = log();

            toolExecutionService.executeTool(log);

            assertEquals(0, connector.executions.get());
            verify(toolCallLogService, never()).finishNonReplayable(any(), any());
            verify(toolCallLogService, never()).recordOutput(any(), any());
            verify(agentDeliveryService, never()).deliverToolResult(any(), any());
        }

        @Test
        @DisplayName("фактический результат финализируется первым: recovery проигрывает CAS и не доставляет")
        void actualResultWinsAgainstRecovery() {
            connector = connector(ToolExecutionPolicy.nonReplayable(TIMEOUT));
            stubConnection();
            AtomicBoolean finished = new AtomicBoolean();
            when(toolCallLogService.claimDispatch(LOG_ID, TIMEOUT)).thenReturn(true);
            when(toolCallLogService.isUnfinished(LOG_ID)).thenReturn(true);
            when(toolCallLogService.finishNonReplayable(eq(LOG_ID), any()))
                    .thenAnswer(invocation -> finished.compareAndSet(false, true));
            ToolCallLog log = log();
            when(toolCallLogService.findRecoverableDispatches(any(), eq(100))).thenReturn(List.of(log));
            NonReplayableToolRecovery recovery = new NonReplayableToolRecovery(
                    toolCallLogService, agentDeliveryService, Runnable::run);

            toolExecutionService.executeTool(log);
            recovery.recover();

            assertEquals(1, connector.executions.get());
            verify(toolCallLogService, times(2)).finishNonReplayable(eq(LOG_ID), any());
            verify(agentDeliveryService, times(1)).deliverToolResult(eq(log), resultWithOutput());
            verify(toolCallLogService, never()).recordOutput(any(), any());
        }

        @Test
        @DisplayName("сбой до claim на захваченной строке ничего не пишет: живой владелец сохраняет исход")
        void preClaimFailureCannotOverwriteUnknown() {
            when(connectorRegistry.getHandler(CONNECTOR)).thenThrow(new ConnectorException("Unknown connector"));
            when(toolCallLogService.isDispatched(LOG_ID)).thenReturn(true);
            ToolCallLog log = log();

            toolExecutionService.executeTool(log);

            verify(toolCallLogService, never()).finishNonReplayable(any(), any());
            verify(toolCallLogService, never()).recordOutput(any(), any());
            verify(agentDeliveryService, never()).deliverToolResult(any(), any());
        }

        @Test
        @DisplayName("сбой до claim на незахваченной строке пишется в журнал как раньше")
        void preClaimFailureOnUndispatchedRowIsRecorded() {
            when(connectorRegistry.getHandler(CONNECTOR)).thenThrow(new ConnectorException("Unknown connector"));
            when(toolCallLogService.isDispatched(LOG_ID)).thenReturn(false);
            ToolCallLog log = log();

            toolExecutionService.executeTool(log);

            verify(toolCallLogService).recordOutput(eq(LOG_ID), errorWith("Unknown connector"));
            verify(toolCallLogService, never()).finishNonReplayable(any(), any());
            verify(agentDeliveryService).deliverToolResult(eq(log), errorWith("Unknown connector"));
        }

        @Test
        @DisplayName("сбой проверки dispatch не пишет ни CAS, ни журнал")
        void dispatchCheckFailureWritesNothing() {
            when(connectorRegistry.getHandler(CONNECTOR)).thenThrow(new ConnectorException("Unknown connector"));
            when(toolCallLogService.isDispatched(LOG_ID)).thenThrow(new IllegalStateException("db is down"));
            ToolCallLog log = log();

            toolExecutionService.executeTool(log);

            verify(toolCallLogService, never()).recordOutput(any(), any());
            verify(toolCallLogService, never()).finishNonReplayable(any(), any());
            verify(agentDeliveryService, never()).deliverToolResult(any(), any());
        }

        @Test
        @DisplayName("отсутствие таймаута падает до claim и до провайдера")
        void missingNonReplayableTimeoutFailsBeforeProvider() {
            connector = new FakeConnector(() -> ToolExecutionPolicy.nonReplayable(null));
            when(connectorRegistry.getHandler(CONNECTOR)).thenReturn(connector);
            ToolCallLog log = log();

            toolExecutionService.executeTool(log);

            assertEquals(0, connector.executions.get());
            verify(toolCallLogService, never()).claimDispatch(any(), any());
            verifyNoInteractions(envFactory);
        }

        @Test
        @DisplayName("дублирующий блокирующий вызов отвечает StillRunning")
        void duplicateBlockingCallReportsStillRunning() {
            connector = connector(ToolExecutionPolicy.nonReplayable(TIMEOUT));
            when(toolCallLogService.claimDispatch(LOG_ID, TIMEOUT)).thenReturn(false);
            runSubmittedInline();
            ToolCallLog log = log();

            WaitOutcome outcome = toolExecutionService.executeWithTimeout(log, Duration.ofMillis(10));

            assertInstanceOf(WaitOutcome.StillRunning.class, outcome);
            assertEquals(0, connector.executions.get());
            verify(toolCallLogService, never()).recordOutput(any(), any());
            verify(agentDeliveryService, never()).deliverToolResult(any(), any());
        }
    }

    private FakeConnector connector(ToolExecutionPolicy policy) {
        FakeConnector fakeConnector = new FakeConnector(() -> policy);
        when(connectorRegistry.getHandler(CONNECTOR)).thenReturn(fakeConnector);
        return fakeConnector;
    }

    private FakeConnector failingConnector(RuntimeException failure) {
        FakeConnector fakeConnector = new FakeConnector(ToolExecutionPolicy::replayable, failure);
        when(connectorRegistry.getHandler(CONNECTOR)).thenReturn(fakeConnector);
        return fakeConnector;
    }

    private static IToolResult resultWithOutput() {
        return argThat(result -> "{\"stdout\":\"ok\"}".equals(result.getOutput()));
    }

    private static IToolResult cancelledResult() {
        return argThat(result -> result.getError() != null && result.getError().startsWith("Cancelled"));
    }

    private static IToolResult errorWith(String error) {
        return argThat(result -> error.equals(result.getError()));
    }

    private void stubConnection() {
        Connection connection = Connection.builder()
                .id(CONNECTION_ID)
                .connectorCode(CONNECTOR)
                .fullCode(CONNECTOR)
                .userId(USER_ID)
                .enabled(true)
                .build();
        when(connectionRepository.findByIdAndUserIdNotDeleted(CONNECTION_ID, USER_ID))
                .thenReturn(Optional.of(connection));
    }

    private static ToolCallLog log() {
        return ToolCallLog.builder()
                .id(LOG_ID)
                .agentId(AGENT_ID)
                .userId(USER_ID)
                .connectorCode(CONNECTOR)
                .connectionId(CONNECTION_ID.toString())
                .externalId("ext-1")
                .name(TOOL)
                .input(Map.of("command", "hostname"))
                .runId(RUN_ID)
                .build();
    }

    /** The production call waits on the executor; the test runs the submitted call on the spot. */
    @SuppressWarnings("unchecked")
    private void runSubmittedInline() {
        when(toolExecutor.submit(ArgumentMatchers.<Callable<Optional<ToolResult>>>any()))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(
                        invocation.<Callable<Optional<ToolResult>>>getArgument(0).call()));
    }

    /**
     * An integration connector whose policy is fixed by the test, counting executions: the assertions
     * are about real side effects (was the provider reached, was the env built), not about mock calls.
     */
    private static final class FakeConnector implements IntegrationConnectorHandler, ToolProvider {

        private final Supplier<ToolExecutionPolicy> policy;
        private final RuntimeException failure;
        private final AtomicInteger executions = new AtomicInteger();

        private FakeConnector(Supplier<ToolExecutionPolicy> policy) {
            this(policy, null);
        }

        private FakeConnector(Supplier<ToolExecutionPolicy> policy, RuntimeException failure) {
            this.policy = policy;
            this.failure = failure;
        }

        @Override
        public String connectorCode() {
            return CONNECTOR;
        }

        @Override
        public Map<String, ConnectorToolSpec> getTools() {
            return Map.of();
        }

        @Override
        public Map<String, CredentialField> getCredentialFields() {
            return Map.of();
        }

        @Override
        public IntegrationValidationResult validateCredentials(Map<String, String> credentials) {
            return IntegrationValidationResult.success(CONNECTOR, "Fake");
        }

        @Override
        public ToolExecutionPolicy executionPolicy(String toolName, Map<String, Object> input) {
            return policy.get();
        }

        @Override
        public Map<String, Object> executeTool(ConnectorEnv env, String toolName, Map<String, Object> args) {
            executions.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return Map.of("stdout", "ok");
        }
    }
}
