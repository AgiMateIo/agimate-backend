package ru.agimate.controlapi.realtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensolutionlab.httpclients.clients.CentrifugoClient;
import org.opensolutionlab.httpclients.models.requests.publication.PublishRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.agimate.controlapi.config.CentrifugoProperties;
import ru.agimate.controlapi.realtime.RealtimeEvent.SessionChanged;
import ru.agimate.controlapi.realtime.RealtimeMessages.Message;
import ru.agimate.controlapi.realtime.dto.CentrifugoMessage;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RealtimePublisher — после коммита, без повторов, сбой не наружу")
class RealtimePublisherTest {

    private static final SessionChanged EVENT = SessionChanged.updated(UUID.randomUUID());
    private static final Message MESSAGE = new Message("user:u", "data", Map.of("entity", "session"));

    @Mock private CentrifugoClient client;
    @Mock private CentrifugoProperties properties;
    @Mock private RealtimeMessages messages;
    @Mock private PlatformTransactionManager transactionManager;

    private RealtimePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new RealtimePublisher(client, properties, messages, JsonMapper.builder().build(),
                transactionManager);
        lenient().when(properties.isEnabled()).thenReturn(true);
        lenient().when(messages.render(any())).thenReturn(List.of(MESSAGE));
    }

    @Test
    @DisplayName("вне транзакции — отправляется сразу, с каналом, данными и тегами")
    void outsideTransactionSendsAtOnce() {
        publisher.publish(EVENT);

        ArgumentCaptor<PublishRequest<?>> request = ArgumentCaptor.forClass(PublishRequest.class);
        verify(client).publish(request.capture());
        assertEquals("user:u", request.getValue().getChannel());
        assertEquals("data", request.getValue().getData());
        assertEquals(Map.of("entity", "session"), request.getValue().getTags());
    }

    @Test
    @DisplayName("дата уходит ISO-строкой, которую переварит и Jackson клиента без модуля java.time")
    void datesSurviveClientJackson() throws Exception {
        record Row(LocalDateTime lastActivityAt) {
        }
        when(messages.render(any())).thenReturn(List.of(new Message("user:u",
                new CentrifugoMessage<>("session.updated", new Row(LocalDateTime.of(2026, 9, 23, 0, 19, 8))),
                Map.of())));

        publisher.publish(EVENT);

        ArgumentCaptor<PublishRequest<?>> request = ArgumentCaptor.forClass(PublishRequest.class);
        verify(client).publish(request.capture());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request.getValue().getData());
        assertEquals("{\"type\":\"session.updated\",\"payload\":{\"lastActivityAt\":\"2026-09-23T00:19:08\"}}", json);
    }

    @Test
    @DisplayName("сбой Centrifugo не выходит наружу")
    void failureSwallowed() {
        doThrow(new RuntimeException("down")).when(client).publish(any(PublishRequest.class));

        assertDoesNotThrow(() -> publisher.publish(EVENT));
    }

    @Test
    @DisplayName("Centrifugo выключен — ничего не публикуется")
    void disabled() {
        when(properties.isEnabled()).thenReturn(false);

        publisher.publish(EVENT);

        verifyNoInteractions(client);
    }

    @Nested
    @DisplayName("в транзакции")
    class InTransaction {

        @BeforeEach
        void begin() {
            TransactionSynchronizationManager.initSynchronization();
        }

        @AfterEach
        void end() {
            TransactionSynchronizationManager.clearSynchronization();
        }

        private void commit() {
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        }

        @Test
        @DisplayName("ждёт коммита, а равные события одной транзакции уходят один раз")
        void waitsForCommitAndCollapses() {
            publisher.publish(EVENT);
            publisher.publish(SessionChanged.updated(EVENT.sessionId()));
            verifyNoInteractions(client);

            commit();

            verify(messages, times(1)).render(EVENT);
            verify(client, times(1)).publish(any(PublishRequest.class));
        }

        @Test
        @DisplayName("откат — пачка не отправляется")
        void rollbackSendsNothing() {
            publisher.publish(EVENT);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            verify(client, never()).publish(any(PublishRequest.class));
        }
    }
}
