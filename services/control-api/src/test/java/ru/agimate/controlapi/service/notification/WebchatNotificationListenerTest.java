package ru.agimate.controlapi.service.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import ru.agimate.controlapi.config.NotificationProperties;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;
import ru.agimate.controlapi.service.webchat.WebchatAgentMessageEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebchatNotificationListener")
class WebchatNotificationListenerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock private NotificationClient notificationClient;
    @Mock private AgentRepository agentRepository;
    @Mock private WebchatMessageRepository webchatMessageRepository;
    @Mock private TaskScheduler taskScheduler;
    @Mock private AsyncTaskExecutor notificationExecutor;

    private NotificationProperties notificationProperties;
    private WebchatNotificationListener listener;

    @BeforeEach
    void setUp() {
        notificationProperties = new NotificationProperties();
        listener = new WebchatNotificationListener(notificationClient, agentRepository,
                webchatMessageRepository, notificationProperties, taskScheduler, notificationExecutor);

        Agent agent = new Agent();
        agent.setName("Секретарь");
        lenient().when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));
    }

    private static WebchatAgentMessageEvent event(String text) {
        return new WebchatAgentMessageEvent(USER_ID, AGENT_ID, SESSION_ID, "m1", text);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> sentData() {
        var captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).notifyUser(eq(USER_ID), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("содержание: имя типа то же, что у события Centrifugo")
    void payloadFields() {
        listener.deliver(event("готово, отчёт собран"));

        Map<String, String> data = sentData();
        assertEquals("webchat_message", data.get("type"));
        assertEquals(SESSION_ID.toString(), data.get("sessionId"));
        assertEquals(AGENT_ID.toString(), data.get("agentId"));
        assertEquals("Секретарь", data.get("agentName"));
        assertEquals("m1", data.get("messageId"));
        assertEquals("готово, отчёт собран", data.get("preview"));
    }

    /** Превью рисуется на заблокированном экране — инсталляция вправе его выключить. */
    @Test
    @DisplayName("preview: false — текста в уведомлении нет, остальное на месте")
    void previewCanBeSwitchedOff() {
        notificationProperties.setPreview(false);

        listener.deliver(event("секретное содержимое"));

        Map<String, String> data = sentData();
        assertFalse(data.containsKey("preview"));
        assertTrue(data.containsKey("sessionId"));
    }

    /** Длинный ответ не должен упереться в лимит сообщения транспорта (4 КБ). */
    @Test
    @DisplayName("превью обрезается тем же правилом, что бейдж")
    void previewIsShortened() {
        listener.deliver(event("я".repeat(500)));

        assertEquals(160, sentData().get("preview").length());
    }

    @Test
    @DisplayName("удалённый агент не роняет отправку")
    void missingAgentDoesNotFail() {
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.empty());

        listener.deliver(event("готово"));

        assertEquals("", sentData().get("agentName"));
    }

    /** Сообщение уже записано и опубликовано — исключение отсюда завалило бы его задним числом. */
    @Test
    @DisplayName("сбой отправки наружу не выходит")
    void sendFailureIsSwallowed() {
        doThrow(new RuntimeException("relay down")).when(notificationClient).notifyUser(any(), any());

        assertDoesNotThrow(() -> listener.deliver(event("готово")));
    }

    /** Читающий переписку в браузере уже видел ответ — телефону нечего добавить. */
    @Test
    @DisplayName("прочитанное за время паузы на телефон не уходит")
    void readMessageIsNotPushed() {
        when(webchatMessageRepository.isRead(SESSION_ID, "m1")).thenReturn(true);

        listener.deliver(event("готово"));

        verify(notificationClient, never()).notifyUser(any(), any());
    }

    @Test
    @DisplayName("отправка откладывается на паузу и уходит на исполнитель уведомлений")
    void deliveryIsDelayed() {
        notificationProperties.setDelay(Duration.ofSeconds(30));
        Instant before = Instant.now();

        listener.onAgentMessage(event("готово"));

        var task = ArgumentCaptor.forClass(Runnable.class);
        var at = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler).schedule(task.capture(), at.capture());
        assertFalse(at.getValue().isBefore(before.plusSeconds(30)));
        verify(notificationClient, never()).notifyUser(any(), any());

        task.getValue().run();
        var send = ArgumentCaptor.forClass(Runnable.class);
        verify(notificationExecutor).execute(send.capture());
        send.getValue().run();
        assertEquals("m1", sentData().get("messageId"));
    }
}
