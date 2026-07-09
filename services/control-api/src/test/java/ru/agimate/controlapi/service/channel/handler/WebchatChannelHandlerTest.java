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
import ru.agimate.controlapi.service.tool.AgentToolCallService;
import ru.agimate.controlapi.service.channel.handler.dto.ChannelConfig;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundDispatch;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.webchat.WebchatMessagePublisher;

import java.util.HashMap;
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
    @Mock
    private AgentToolCallService toolCallService;

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
            OutboundDispatch dispatch = new OutboundDispatch("msg-1", null, CHANNEL_ID, SESSION_ID, Map.of());

            handler.handleOutput(config, OutboundMessage.text("готово"), dispatch, toolCallService);

            verify(webchatMessagePublisher).record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                    WebchatMessageDirection.AGENT, "answer", "msg-1", "готово");
            verifyNoInteractions(toolCallService);
        }

        @Test
        @DisplayName("stream=progress прокидывается как есть")
        void passesStreamThrough() {
            when(channelRepository.findByIdAndDeletedAtIsNull(CHANNEL_ID)).thenReturn(Optional.of(channel));
            OutboundDispatch dispatch = new OutboundDispatch("msg-2", "progress", CHANNEL_ID, SESSION_ID, Map.of());

            handler.handleOutput(config, OutboundMessage.text("думаю..."), dispatch, toolCallService);

            verify(webchatMessagePublisher).record(USER_ID, AGENT_ID, CHANNEL_ID, SESSION_ID,
                    WebchatMessageDirection.AGENT, "progress", "msg-2", "думаю...");
        }

        @Test
        @DisplayName("канал не найден — ConnectorException")
        void missingChannel() {
            when(channelRepository.findByIdAndDeletedAtIsNull(CHANNEL_ID)).thenReturn(Optional.empty());
            OutboundDispatch dispatch = new OutboundDispatch("msg-3", null, CHANNEL_ID, SESSION_ID, Map.of());

            assertThrows(ConnectorException.class,
                    () -> handler.handleOutput(config, OutboundMessage.text("x"), dispatch, toolCallService));
            verifyNoInteractions(webchatMessagePublisher);
        }
    }
}
