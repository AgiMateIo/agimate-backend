package ru.agimate.controlapi.service.webchat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import ru.agimate.controlapi.database.enums.WebchatMessageDirection;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;
import ru.agimate.controlapi.realtime.RealtimeEvent.WebchatMessageRecorded;
import ru.agimate.controlapi.realtime.RealtimePublisher;
import ru.agimate.controlapi.service.channel.handler.dto.Part;
import ru.agimate.controlapi.service.session.AgentSessionService;
import ru.agimate.controlapi.storage.FileIds;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebchatMessagePublisher")
class WebchatMessagePublisherTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID CHANNEL_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock private WebchatMessageRepository webchatMessageRepository;
    @Mock private RealtimePublisher realtime;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AgentSessionService agentSessionService;

    @InjectMocks private WebchatMessagePublisher publisher;

    @Test
    @DisplayName("ответ агента поднимает событие для пуша, эхо пользователя и progress — нет")
    void pushEventOnlyForAnswers() {
        publisher.record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                WebchatMessageDirection.AGENT, "answer", "m1", "готово", null);
        publisher.record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                WebchatMessageDirection.AGENT, "progress", "m2", "думаю", null);
        publisher.record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                WebchatMessageDirection.USER, null, "m3", "привет", null);

        var captor = ArgumentCaptor.forClass(WebchatAgentMessageEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(SESSION_ID, captor.getValue().sessionId());
        assertEquals("m1", captor.getValue().messageId());
        assertEquals("готово", captor.getValue().text());
    }

    @Test
    @DisplayName("строку сессии двигают ответ и своё сообщение, progress — нет")
    void sessionChangedForAnswersAndEcho() {
        publisher.record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                WebchatMessageDirection.AGENT, "answer", "m1", "готово", null);
        publisher.record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                WebchatMessageDirection.AGENT, "progress", "m2", "думаю", null);
        publisher.record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                WebchatMessageDirection.USER, null, "m3", "привет", null);

        verify(agentSessionService, times(2)).messageRecorded(SESSION_ID);
    }

    @Test
    @DisplayName("без вложений: parts null и в строке, и в событии")
    void noParts() {
        publisher.record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                WebchatMessageDirection.USER, null, "m1", "привет", null);

        verify(webchatMessageRepository).insertIgnoreConflict(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                "USER", null, "m1", "привет", null);
        assertNull(capturedEvent().parts());
    }

    @Test
    @DisplayName("вложение: и строка, и событие хранят fileId без url — ссылку подписывают при отправке")
    void partsStoredWithoutUrl() {
        String fileId = FileIds.external(UUID.randomUUID());

        publisher.record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                WebchatMessageDirection.AGENT, "answer", "m2", "вот скриншот",
                List.of(new Part("image", fileId, 1, "image/png", 42, Map.of())));

        ArgumentCaptor<String> partsJson = ArgumentCaptor.forClass(String.class);
        verify(webchatMessageRepository).insertIgnoreConflict(eq(USER_ID), eq(AGENT_ID), eq(CHANNEL_ID),
                eq(SESSION_ID), eq("AGENT"), eq("answer"), eq("m2"), eq("вот скриншот"),
                partsJson.capture());
        assertTrue(partsJson.getValue().contains("\"fileId\":\"" + fileId + "\""));
        assertFalse(partsJson.getValue().contains("url"));

        WebchatMessageRecorded event = capturedEvent();
        assertEquals(1, event.parts().size());
        assertEquals(fileId, event.parts().get(0).get("fileId"));
        assertFalse(event.parts().get(0).containsKey("url"));
    }

    @Test
    @DisplayName("повтор доставки (строка уже есть) — событие публикуется снова, клиент уберёт дубль")
    void replayRepublishes() {
        when(webchatMessageRepository.insertIgnoreConflict(any(), any(), any(), any(), anyString(), any(),
                anyString(), any(), any())).thenReturn(0);

        publisher.record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                WebchatMessageDirection.AGENT, "answer", "m3", "готово", null);

        assertEquals("m3", capturedEvent().messageId());
    }

    private WebchatMessageRecorded capturedEvent() {
        ArgumentCaptor<WebchatMessageRecorded> event = ArgumentCaptor.forClass(WebchatMessageRecorded.class);
        verify(realtime).publish(event.capture());
        return event.getValue();
    }
}
