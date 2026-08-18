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
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;
import ru.agimate.controlapi.service.channel.handler.dto.Part;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.FileLink;
import ru.agimate.controlapi.storage.SignedFileUrlService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
    @Mock private CentrifugoService centrifugoService;
    @Mock private SignedFileUrlService signedFileUrlService;
    @Mock private ApplicationEventPublisher eventPublisher;

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
    @DisplayName("без вложений: parts null и в строке, и в событии")
    void noParts() {
        publisher.record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                WebchatMessageDirection.USER, null, "m1", "привет", null);

        verify(webchatMessageRepository).insertIgnoreConflict(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                "USER", null, "m1", "привет", null);
        assertNull(capturedEvent().parts());
    }

    @Test
    @DisplayName("вложение: строка хранит fileId без url, событие несёт свежую подписанную ссылку")
    void partsStoredWithoutUrlEventWithUrl() {
        String fileId = FileIds.external(UUID.randomUUID());
        when(signedFileUrlService.issue(new FileLink(USER_ID, fileId, "image/png", null)))
                .thenReturn("/files/" + fileId + "?exp=1&sig=s");

        publisher.record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                WebchatMessageDirection.AGENT, "answer", "m2", "вот скриншот",
                List.of(new Part("image", fileId, "image/png", 42, Map.of())));

        ArgumentCaptor<String> partsJson = ArgumentCaptor.forClass(String.class);
        verify(webchatMessageRepository).insertIgnoreConflict(eq(USER_ID), eq(AGENT_ID), eq(CHANNEL_ID),
                eq(SESSION_ID), eq("AGENT"), eq("answer"), eq("m2"), eq("вот скриншот"),
                partsJson.capture());
        assertTrue(partsJson.getValue().contains("\"fileId\":\"" + fileId + "\""));
        assertFalse(partsJson.getValue().contains("url"));

        WebchatMessageEvent event = capturedEvent();
        assertEquals(1, event.parts().size());
        WebchatAttachment attachment = event.parts().get(0);
        assertEquals("image", attachment.type());
        assertEquals(fileId, attachment.fileId());
        assertEquals("image/png", attachment.mime());
        assertEquals(42L, attachment.size());
        assertEquals("/files/" + fileId + "?exp=1&sig=s", attachment.url());
    }

    @Test
    @DisplayName("ответ агента дублируется бейджем в user:{userId} с тегами и обрезанным превью")
    void answerFansOutToUserChannel() {
        String longText = "а".repeat(WebchatPreviews.MAX_LENGTH + 20);

        publisher.record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                WebchatMessageDirection.AGENT, "answer", "m3", longText, null);

        WebchatActivityEvent event = capturedActivity();
        assertEquals(AGENT_ID, event.agentId());
        assertEquals(SESSION_ID, event.sessionId());
        assertEquals("m3", event.messageId());
        assertEquals("answer", event.stream());
        assertEquals(WebchatPreviews.MAX_LENGTH, event.preview().length());
    }

    @Test
    @DisplayName("progress и эхо пользователя бейдж не поднимают")
    void progressAndUserDoNotFanOut() {
        publisher.record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                WebchatMessageDirection.AGENT, "progress", "m4", "читаю таблицу", null);
        publisher.record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                WebchatMessageDirection.USER, null, "m5", "привет", null);

        verify(centrifugoService, never()).publishMessage(
                eq(WebchatMessagePublisher.USER_CHANNEL_PREFIX + USER_ID), any(), any(), anyMap());
    }

    @Test
    @DisplayName("падение публикации бейджа не роняет доставку сообщения")
    void activityFailureSwallowed() {
        // lenient: strict stubbing tells overloads apart by name, so the 3-argument publish of the
        // message itself would read as a mismatch against this stub of the 4-argument one.
        lenient().doThrow(new RuntimeException("centrifugo down")).when(centrifugoService)
                .publishMessage(eq(WebchatMessagePublisher.USER_CHANNEL_PREFIX + USER_ID),
                        any(), any(), anyMap());

        assertDoesNotThrow(() -> publisher.record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                WebchatMessageDirection.AGENT, "answer", "m6", "готово", null));

        verify(webchatMessageRepository).insertIgnoreConflict(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                "AGENT", "answer", "m6", "готово", null);
    }

    private WebchatActivityEvent capturedActivity() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(centrifugoService).publishMessage(
                eq(WebchatMessagePublisher.USER_CHANNEL_PREFIX + USER_ID),
                eq(WebchatMessagePublisher.ACTIVITY_EVENT_TYPE), payload.capture(),
                eq(Map.of("entity", "webchat.message", "agentId", AGENT_ID.toString())));
        return (WebchatActivityEvent) payload.getValue();
    }

    private WebchatMessageEvent capturedEvent() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(centrifugoService).publishMessage(
                eq(WebchatMessagePublisher.CENTRIFUGO_CHANNEL_PREFIX + SESSION_ID),
                eq(WebchatMessagePublisher.EVENT_TYPE), payload.capture());
        return (WebchatMessageEvent) payload.getValue();
    }
}
