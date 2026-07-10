package ru.agimate.controlapi.service.channel;

import dev.dbos.transact.DBOSClient;
import dev.dbos.transact.workflow.ListWorkflowsInput;
import dev.dbos.transact.workflow.WorkflowState;
import dev.dbos.transact.workflow.WorkflowStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.TriggerLogAgent;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.repositories.TriggerLogAgentRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AgentRunRegistryService")
class AgentRunRegistryServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID HOLDER_RUN_ID = UUID.randomUUID();

    private final TriggerLogAgentRepository repository = mock(TriggerLogAgentRepository.class);
    private final DBOSClient dbosClient = mock(DBOSClient.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DBOSClient> clientProvider = mock(ObjectProvider.class);
    private final AgentRunRegistryService service = new AgentRunRegistryService(repository, clientProvider);

    private void givenRun(UUID sessionId, RunStatus status) {
        when(repository.findById(RUN_ID)).thenReturn(Optional.of(TriggerLogAgent.builder()
                .id(RUN_ID)
                .agent(Agent.builder().id(AGENT_ID).build())
                .sessionId(sessionId)
                .status(status)
                .build()));
    }

    @Nested
    @DisplayName("registerRun: воркер знает только trigger_id, сессию резолвит бэк")
    class RegisterRun {

        @Test
        @DisplayName("слот взят: ACQUIRED + session_key")
        void acquired() {
            givenRun(SESSION_ID, RunStatus.ENQUEUED);
            when(repository.markRunning(eq(RUN_ID), eq(SESSION_ID), any(), any())).thenReturn(1);

            var result = service.registerRun(AGENT_ID, RUN_ID, 60);

            assertEquals(AgentRunRegistryService.SlotStatus.ACQUIRED, result.status());
            assertEquals(SESSION_ID, result.sessionId());
        }

        @Test
        @DisplayName("direct-ран без сессии: NO_SESSION, статус строки не трогается")
        void noSession() {
            givenRun(null, RunStatus.ENQUEUED);

            var result = service.registerRun(AGENT_ID, RUN_ID, 60);

            assertEquals(AgentRunRegistryService.SlotStatus.NO_SESSION, result.status());
            verify(repository, never()).markRunning(any(), any(), any(), any());
        }

        @Test
        @DisplayName("markRunning не прошёл, но ран жив (ENQUEUED) — BUSY с session_key")
        void busySlot() {
            givenRun(SESSION_ID, RunStatus.ENQUEUED);
            when(repository.markRunning(eq(RUN_ID), eq(SESSION_ID), any(), any())).thenReturn(0);

            var result = service.registerRun(AGENT_ID, RUN_ID, 60);

            assertEquals(AgentRunRegistryService.SlotStatus.BUSY, result.status());
            assertEquals(SESSION_ID, result.sessionId());
        }

        @Test
        @DisplayName("строки рана нет — NotFound")
        void missingRunRejected() {
            when(repository.findById(RUN_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundStatusException.class, () -> service.registerRun(AGENT_ID, RUN_ID, 60));
        }

        @Test
        @DisplayName("поздний register на завершённый ран (DONE) — NotFound, слот не переоккупируется")
        void terminalRunRejected() {
            givenRun(SESSION_ID, RunStatus.DONE);
            when(repository.markRunning(eq(RUN_ID), eq(SESSION_ID), any(), any())).thenReturn(0);

            assertThrows(NotFoundStatusException.class, () -> service.registerRun(AGENT_ID, RUN_ID, 60));
        }

        @Test
        @DisplayName("ран чужого агента — BadRequest")
        void foreignAgentRejected() {
            givenRun(SESSION_ID, RunStatus.ENQUEUED);

            assertThrows(BadRequestStatusException.class,
                    () -> service.registerRun(UUID.randomUUID(), RUN_ID, 60));
        }
    }

    @Nested
    @DisplayName("reclaimDeadHolder")
    class ReclaimDeadHolder {

        private void givenHolder(LocalDateTime expiresAt) {
            when(repository.findBySessionIdAndStatus(SESSION_ID, RunStatus.RUNNING))
                    .thenReturn(Optional.of(TriggerLogAgent.builder()
                            .id(HOLDER_RUN_ID).status(RunStatus.RUNNING).expiresAt(expiresAt).build()));
        }

        private void givenWorkflowState(WorkflowState state) {
            WorkflowStatus status = mock(WorkflowStatus.class);
            when(status.status()).thenReturn(state);
            when(dbosClient.listWorkflows(any(ListWorkflowsInput.class))).thenReturn(List.of(status));
        }

        @Test
        @DisplayName("держатель уже освободился (гонка с release) — слот свободен")
        void holderAlreadyGone() {
            when(repository.findBySessionIdAndStatus(SESSION_ID, RunStatus.RUNNING))
                    .thenReturn(Optional.empty());

            assertTrue(service.reclaimDeadHolder(SESSION_ID));
            verify(repository, never()).releaseOwn(any(), any());
        }

        @Test
        @DisplayName("истёкший TTL — вытесняет без обращения к DBOS (индекс не знает про expires_at)")
        void expiredHolderEvicted() {
            givenHolder(LocalDateTime.now().minusMinutes(1));
            when(repository.releaseOwn(HOLDER_RUN_ID, RunStatus.FAILED)).thenReturn(1);

            assertTrue(service.reclaimDeadHolder(SESSION_ID));

            verify(repository).releaseOwn(HOLDER_RUN_ID, RunStatus.FAILED);
            verifyNoInteractions(dbosClient);
        }

        @Test
        @DisplayName("лизинг жив, DBOSClient недоступен (dbos.enabled=false) — не вытесняет")
        void noClientNoReclaim() {
            givenHolder(LocalDateTime.now().plusHours(1));
            when(clientProvider.getIfAvailable()).thenReturn(null);

            assertFalse(service.reclaimDeadHolder(SESSION_ID));
            verify(repository, never()).releaseOwn(any(), any());
        }

        @Test
        @DisplayName("живой держатель (PENDING) — не вытесняет")
        void liveHolderKept() {
            givenHolder(LocalDateTime.now().plusHours(1));
            when(clientProvider.getIfAvailable()).thenReturn(dbosClient);
            givenWorkflowState(WorkflowState.PENDING);

            assertFalse(service.reclaimDeadHolder(SESSION_ID));
            verify(repository, never()).releaseOwn(any(), any());
        }

        @Test
        @DisplayName("воркфлоу держателя в терминальном статусе (ERROR) — вытесняет как FAILED")
        void deadHolderEvicted() {
            givenHolder(LocalDateTime.now().plusHours(1));
            when(clientProvider.getIfAvailable()).thenReturn(dbosClient);
            givenWorkflowState(WorkflowState.ERROR);
            when(repository.releaseOwn(HOLDER_RUN_ID, RunStatus.FAILED)).thenReturn(1);

            assertTrue(service.reclaimDeadHolder(SESSION_ID));

            verify(repository).releaseOwn(HOLDER_RUN_ID, RunStatus.FAILED);
            // Запрос статуса не должен грузить input/output воркфлоу: они сериализованы
            // worker-only классами, которых нет в classpath control-api.
            ArgumentCaptor<ListWorkflowsInput> input = ArgumentCaptor.forClass(ListWorkflowsInput.class);
            verify(dbosClient).listWorkflows(input.capture());
            assertEquals(List.of(HOLDER_RUN_ID.toString()), input.getValue().workflowIds());
            assertEquals(Boolean.FALSE, input.getValue().loadInput());
            assertEquals(Boolean.FALSE, input.getValue().loadOutput());
        }

        @Test
        @DisplayName("воркфлоу держателя не найден в DBOS — считается мёртвым, вытесняет")
        void missingWorkflowEvicted() {
            givenHolder(LocalDateTime.now().plusHours(1));
            when(clientProvider.getIfAvailable()).thenReturn(dbosClient);
            when(dbosClient.listWorkflows(any(ListWorkflowsInput.class))).thenReturn(List.of());
            when(repository.releaseOwn(HOLDER_RUN_ID, RunStatus.FAILED)).thenReturn(1);

            assertTrue(service.reclaimDeadHolder(SESSION_ID));
            verify(repository).releaseOwn(HOLDER_RUN_ID, RunStatus.FAILED);
        }
    }
}
