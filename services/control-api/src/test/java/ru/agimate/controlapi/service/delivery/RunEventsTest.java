package ru.agimate.controlapi.service.delivery;

import dev.dbos.transact.DBOSClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.agimate.agentworker.RunEvent;
import ru.agimate.agentworker.WorkerProtocol;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("RunEvents — уведомления воркфлоу рана поверх сообщений DBOS")
class RunEventsTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<DBOSClient> clientProvider = mock(ObjectProvider.class);
    private final AgentRunRepository agentRunRepository = mock(AgentRunRepository.class);
    private final DBOSClient client = mock(DBOSClient.class);

    private final RunEvents events = new RunEvents(clientProvider, agentRunRepository);

    private final UUID runId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("результат тула уходит в воркфлоу рана с ключом идемпотентности, называющим адресата")
    void toolFinishedSendsToTheRun() {
        when(clientProvider.getIfAvailable()).thenReturn(client);

        events.toolFinished(runId, "call-1");

        verify(client).send(eq(runId.toString()), eq(RunEvent.toolFinished("call-1")),
                eq(WorkerProtocol.RUN_EVENTS_TOPIC), eq("tool:" + runId + ":call-1"));
    }

    @Test
    @DisplayName("ключ разный у разных адресатов: одинаковый id вызова в двух ранах не гасит второе сообщение")
    void keysAreScopedToTheirDestination() {
        UUID secondRun = UUID.randomUUID();
        when(clientProvider.getIfAvailable()).thenReturn(client);

        events.toolFinished(runId, "call-1");
        events.toolFinished(secondRun, "call-1");

        verify(client).send(eq(runId.toString()), any(),
                eq(WorkerProtocol.RUN_EVENTS_TOPIC), eq("tool:" + runId + ":call-1"));
        verify(client).send(eq(secondRun.toString()), any(),
                eq(WorkerProtocol.RUN_EVENTS_TOPIC), eq("tool:" + secondRun + ":call-1"));
    }

    @Test
    @DisplayName("новый ран в сессии будит каждый работающий ран этой сессии")
    void inputArrivedNotifiesEveryRunningRun() {
        UUID newRunId = UUID.randomUUID();
        UUID anotherRunning = UUID.randomUUID();
        when(clientProvider.getIfAvailable()).thenReturn(client);
        when(agentRunRepository.findRunningIdsBySession(sessionId))
                .thenReturn(List.of(runId, anotherRunning));

        events.inputArrived(sessionId, newRunId);

        verify(client).send(eq(runId.toString()), eq(RunEvent.steerable(newRunId.toString())),
                eq(WorkerProtocol.RUN_EVENTS_TOPIC), eq("input:" + runId + ":" + newRunId));
        verify(client).send(eq(anotherRunning.toString()), eq(RunEvent.steerable(newRunId.toString())),
                eq(WorkerProtocol.RUN_EVENTS_TOPIC), eq("input:" + anotherRunning + ":" + newRunId));
    }

    @Test
    @DisplayName("сбой поиска адресатов не выходит наружу: уведомление не рвёт создание рана")
    void addressLookupFailureIsSwallowed() {
        when(clientProvider.getIfAvailable()).thenReturn(client);
        when(agentRunRepository.findRunningIdsBySession(sessionId))
                .thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> events.inputArrived(sessionId, UUID.randomUUID()));

        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("адресаты ищутся после коммита: запрос не может откатить транзакцию вызывающего")
    void addressesAreResolvedAfterCommit() {
        when(clientProvider.getIfAvailable()).thenReturn(client);
        when(agentRunRepository.findRunningIdsBySession(sessionId)).thenReturn(List.of(runId));
        TransactionSynchronizationManager.initSynchronization();

        events.inputArrived(sessionId, UUID.randomUUID());

        verifyNoInteractions(agentRunRepository);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        verify(agentRunRepository).findRunningIdsBySession(sessionId);
    }

    @Test
    @DisplayName("у рана без сессии будить некого — запрос за адресатами не делается")
    void directRunHasNoOneToWake() {
        when(clientProvider.getIfAvailable()).thenReturn(client);

        events.inputArrived(null, UUID.randomUUID());

        verifyNoInteractions(agentRunRepository, client);
    }

    @Test
    @DisplayName("dbos.enabled=false — отправка молчит, а не падает")
    void withoutDbosNothingIsSent() {
        when(clientProvider.getIfAvailable()).thenReturn(null);

        assertDoesNotThrow(() -> events.toolFinished(runId, "call-1"));

        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("сбой отправки не выходит наружу: уведомление best-effort")
    void sendFailureIsSwallowed() {
        when(clientProvider.getIfAvailable()).thenReturn(client);
        doThrow(new IllegalStateException("db down"))
                .when(client).send(any(), any(), any(), any());

        assertDoesNotThrow(() -> events.cancelRequested(List.of(runId)));

        verify(client).send(eq(runId.toString()), eq(RunEvent.cancelRequested()),
                eq(WorkerProtocol.RUN_EVENTS_TOPIC), eq("cancel:" + runId));
    }

    @Test
    @DisplayName("внутри транзакции отправка ждёт коммита")
    void sendWaitsForCommit() {
        when(clientProvider.getIfAvailable()).thenReturn(client);
        TransactionSynchronizationManager.initSynchronization();

        events.toolFinished(runId, "call-1");

        verify(client, never()).send(any(), any(), any(), any());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        verify(client).send(eq(runId.toString()), eq(RunEvent.toolFinished("call-1")),
                eq(WorkerProtocol.RUN_EVENTS_TOPIC), eq("tool:" + runId + ":call-1"));
    }
}
