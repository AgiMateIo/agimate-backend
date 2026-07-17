package ru.agimate.controlapi.service.webchat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.enums.WebchatMessageDirection;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;
import ru.agimate.controlapi.service.channel.handler.dto.Part;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.SignedFileUrlService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
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

    @InjectMocks private WebchatMessagePublisher publisher;

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
        when(signedFileUrlService.issue(fileId)).thenReturn("/files/" + fileId + "?exp=1&sig=s");

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

    private WebchatMessageEvent capturedEvent() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(centrifugoService).publishMessage(
                eq(WebchatMessagePublisher.CENTRIFUGO_CHANNEL_PREFIX + SESSION_ID),
                eq(WebchatMessagePublisher.EVENT_TYPE), payload.capture());
        return (WebchatMessageEvent) payload.getValue();
    }
}
