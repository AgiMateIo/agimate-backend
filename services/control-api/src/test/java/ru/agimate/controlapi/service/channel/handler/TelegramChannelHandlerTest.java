package ru.agimate.controlapi.service.channel.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.service.channel.ChannelOutboundDispatcher;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("TelegramChannelHandler")
class TelegramChannelHandlerTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String IDENTITY = "bot-creds-1";

    @Mock
    private ChannelOutboundDispatcher dispatcher;

    private TelegramChannelHandler handler;
    private ChannelConfig config;

    @BeforeEach
    void setUp() {
        handler = new TelegramChannelHandler(dispatcher);
        config = new ChannelConfig("telegram", IDENTITY, Map.of());
    }

    private Trigger trigger(String name, Map<String, Object> data) {
        return Trigger.createBasic("telegram", IDENTITY, name, data);
    }

    @Nested
    @DisplayName("metadata")
    class Metadata {

        @Test
        @DisplayName("exposes all five telegram triggers")
        void triggers() {
            assertEquals(5, handler.listOfTriggers(config).size());
            assertTrue(handler.listOfTriggers(config).stream()
                    .anyMatch(t -> t.triggerName().equals("telegram.message_received")));
        }

        @Test
        @DisplayName("reply tool is send_message bound to channel connector/identity")
        void tools() {
            ToolDefinition tool = handler.listOfTools(config).get(0);
            assertEquals("telegram", tool.connectorCode());
            assertEquals(IDENTITY, tool.identity());
            assertEquals("telegram.send_message", tool.toolName());
        }

        @Test
        @DisplayName("validateConfig rejects non-telegram connector")
        void validateConfigRejectsWrongConnector() {
            ChannelConfig wrong = new ChannelConfig("slack", IDENTITY, Map.of());
            assertThrows(ConnectorException.class, () -> handler.validateConfig(wrong));
        }
    }

    @Nested
    @DisplayName("convert")
    class Convert {

        @Test
        @DisplayName("plain text message")
        void message() {
            String text = handler.convert(config,
                    trigger("telegram.message_received", Map.of("chatId", 42, "text", "Привет"))).orElseThrow().text();
            assertEquals("Привет", text);
        }

        @Test
        @DisplayName("command keeps the full command line")
        void command() {
            String text = handler.convert(config,
                    trigger("telegram.command_received", Map.of("chatId", 42, "text", "/start now"))).orElseThrow().text();
            assertEquals("/start now", text);
        }

        @Test
        @DisplayName("callback query is described")
        void callback() {
            String text = handler.convert(config,
                    trigger("telegram.callback_query", Map.of("chatId", 42, "data", "yes"))).orElseThrow().text();
            assertEquals("[Нажата кнопка] yes", text);
        }

        @Test
        @DisplayName("photo without caption is described, no file fetched")
        void photo() {
            String text = handler.convert(config,
                    trigger("telegram.photo_received", Map.of("chatId", 42))).orElseThrow().text();
            assertEquals("[Пользователь отправил изображение]", text);
        }

        @Test
        @DisplayName("photo caption is appended")
        void photoWithCaption() {
            String text = handler.convert(config,
                    trigger("telegram.photo_received", Map.of("chatId", 42, "caption", "смотри"))).orElseThrow().text();
            assertEquals("[Пользователь отправил изображение] смотри", text);
        }

        @Test
        @DisplayName("document uses its file name")
        void document() {
            String text = handler.convert(config,
                    trigger("telegram.document_received",
                            Map.of("chatId", 42, "document", Map.of("file_name", "report.pdf")))).orElseThrow().text();
            assertEquals("[Пользователь отправил документ: report.pdf]", text);
        }

        @Test
        @DisplayName("conversationKey is the chat id")
        void conversationKey() {
            String key = handler.convert(config,
                    trigger("telegram.message_received", Map.of("chatId", 42, "text", "hi"))).orElseThrow().conversationKey();
            assertEquals("42", key);
        }
    }

    @Nested
    @DisplayName("process")
    class Process {

        @Test
        @DisplayName("dispatches send_message with chatId from reply context")
        void dispatches() {
            OutboundMessage outbound = OutboundMessage.text("Готово", Map.of("chatId", 42));
            ChannelOutboundContext ctx = new ChannelOutboundContext(AGENT_ID, USER_ID, "call-1");

            handler.process(config, outbound, ctx);

            ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
            verify(dispatcher).dispatch(eq(AGENT_ID), eq(USER_ID), eq("telegram"), eq(IDENTITY),
                    eq("telegram.send_message"), args.capture(), eq("call-1"));
            assertEquals("42", args.getValue().get("chatId"));
            assertEquals("Готово", args.getValue().get("text"));
        }

        @Test
        @DisplayName("throws when chatId is missing")
        void missingChatId() {
            OutboundMessage outbound = OutboundMessage.text("Готово", Map.of());
            ChannelOutboundContext ctx = new ChannelOutboundContext(AGENT_ID, USER_ID, "call-1");

            assertThrows(ConnectorException.class, () -> handler.process(config, outbound, ctx));
            verifyNoInteractions(dispatcher);
        }
    }
}
