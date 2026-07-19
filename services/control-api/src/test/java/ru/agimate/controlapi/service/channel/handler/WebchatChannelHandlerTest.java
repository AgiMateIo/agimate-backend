package ru.agimate.controlapi.service.channel.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.enums.WebchatMessageDirection;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.service.channel.handler.dto.ChannelConfig;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundDispatch;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.Part;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.webchat.WebchatMessagePublisher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebchatChannelHandler")
class WebchatChannelHandlerTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CHANNEL_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final String IDENTITY = UUID.randomUUID().toString();

    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private WebchatMessagePublisher webchatMessagePublisher;

    private WebchatChannelHandler handler;
    private ChannelConfig config;

    @BeforeEach
    void setUp() {
        handler = new WebchatChannelHandler(channelRepository, webchatMessagePublisher);
        config = new ChannelConfig(AGENT_ID, "webchat", IDENTITY, Map.of());
    }

    @Nested
    @DisplayName("metadata")
    class Metadata {

        @Test
        @DisplayName("единственный триггер message_received, тулов нет, progress доставляется")
        void metadata() {
            assertEquals(1, handler.listOfTriggers(config).size());
            assertEquals("message_received", handler.listOfTriggers(config).get(0).triggerName());
            assertTrue(handler.listOfTools(config).isEmpty());
            assertTrue(handler.deliverProgress(config));
        }

        @Test
        @DisplayName("supportsOutboundAttachments = true")
        void supportsAttachments() {
            assertTrue(handler.supportsOutboundAttachments());
        }

        @Test
        @DisplayName("validateConfig отклоняет чужой connectorCode")
        void validateConfigRejectsWrongConnector() {
            ChannelConfig wrong = new ChannelConfig(AGENT_ID, "telegram", IDENTITY, Map.of());
            assertThrows(ConnectorException.class, () -> handler.validateConfig(wrong));
        }
    }

    @Nested
    @DisplayName("handleInput")
    class Input {

        @Test
        @DisplayName("извлекает text из trigger.data")
        void extractsText() {
            Trigger trigger = Trigger.createBasic("webchat", IDENTITY, "message_received",
                    Map.of("sessionId", SESSION_ID.toString(), "text", "привет"));
            Optional<InboundMessage> inbound = handler.handleInput(config, trigger);
            assertEquals("привет", inbound.orElseThrow().text());
        }

        @Test
        @DisplayName("пустой text — фильтр (empty)")
        void blankTextFiltered() {
            Map<String, Object> data = new HashMap<>();
            data.put("text", "  ");
            Trigger trigger = Trigger.createBasic("webchat", IDENTITY, "message_received", data);
            assertTrue(handler.handleInput(config, trigger).isEmpty());
        }

        @Test
        @DisplayName("parts из data → InboundMessage.parts + текст-стаб")
        void mapsParts() {
            Trigger trigger = Trigger.createBasic("webchat", IDENTITY, "message_received", Map.of(
                    "text", "что тут?",
                    "parts", List.of(Map.of(
                            "type", "image", "fileId", "agf_1", "mime", "image/png", "size", 4096))));
            InboundMessage inbound = handler.handleInput(config, trigger).orElseThrow();
            assertEquals(1, inbound.parts().size());
            assertEquals("agf_1", inbound.parts().get(0).storageRef());
            assertEquals("image", inbound.parts().get(0).type());
            assertTrue(inbound.text().startsWith("что тут?"));
            assertTrue(inbound.text().contains("agf_1"));
        }

        @Test
        @DisplayName("только parts, пустой text → сообщение из одних стабов")
        void partsOnly() {
            Trigger trigger = Trigger.createBasic("webchat", IDENTITY, "message_received", Map.of(
                    "parts", List.of(Map.of(
                            "type", "image", "fileId", "agf_1", "mime", "image/png", "size", 4096))));
            InboundMessage inbound = handler.handleInput(config, trigger).orElseThrow();
            assertEquals(1, inbound.parts().size());
            assertTrue(inbound.text().contains("agf_1"));
        }
    }

    @Nested
    @DisplayName("handleOutput")
    class Output {

        private final Channel channel = Channel.builder()
                .id(CHANNEL_ID)
                .userId(USER_ID)
                .agentId(AGENT_ID)
                .connectorCode("webchat")
                .connectionId(UUID.fromString(IDENTITY))
                .build();

        @Test
        @DisplayName("пишет AGENT-строку через publisher; пустой stream трактуется как answer")
        void recordsWithDefaultStream() {
            when(channelRepository.findByIdAndDeletedAtIsNull(CHANNEL_ID)).thenReturn(Optional.of(channel));
            OutboundDispatch dispatch = new OutboundDispatch("msg-1", null, null, CHANNEL_ID, SESSION_ID, Map.of());

            assertTrue(handler.handleOutput(config, OutboundMessage.text("готово"), dispatch).isEmpty());

            verify(webchatMessagePublisher).record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                    WebchatMessageDirection.AGENT, "answer", "msg-1", "готово", List.of());
        }

        @Test
        @DisplayName("parts ответа прокидываются в publisher как есть")
        void passesPartsThrough() {
            when(channelRepository.findByIdAndDeletedAtIsNull(CHANNEL_ID)).thenReturn(Optional.of(channel));
            List<Part> parts = List.of(new Part("image", "agf_" + UUID.randomUUID(), "image/png", 5, Map.of()));
            OutboundDispatch dispatch = new OutboundDispatch("msg-4", null, null, CHANNEL_ID, SESSION_ID, Map.of());

            handler.handleOutput(config, new OutboundMessage("вот скриншот", parts), dispatch);

            verify(webchatMessagePublisher).record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                    WebchatMessageDirection.AGENT, "answer", "msg-4", "вот скриншот", parts);
        }

        @Test
        @DisplayName("stream=progress прокидывается как есть")
        void passesStreamThrough() {
            when(channelRepository.findByIdAndDeletedAtIsNull(CHANNEL_ID)).thenReturn(Optional.of(channel));
            OutboundDispatch dispatch = new OutboundDispatch("msg-2", "progress", "THINKING", CHANNEL_ID, SESSION_ID, Map.of());

            handler.handleOutput(config, OutboundMessage.text("думаю..."), dispatch);

            verify(webchatMessagePublisher).record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                    WebchatMessageDirection.AGENT, "progress", "msg-2", "думаю...", List.of());
        }

        @Test
        @DisplayName("канал не найден — ConnectorException")
        void missingChannel() {
            when(channelRepository.findByIdAndDeletedAtIsNull(CHANNEL_ID)).thenReturn(Optional.empty());
            OutboundDispatch dispatch = new OutboundDispatch("msg-3", null, null, CHANNEL_ID, SESSION_ID, Map.of());

            assertThrows(ConnectorException.class,
                    () -> handler.handleOutput(config, OutboundMessage.text("x"), dispatch));
            verifyNoInteractions(webchatMessagePublisher);
        }
    }
}
