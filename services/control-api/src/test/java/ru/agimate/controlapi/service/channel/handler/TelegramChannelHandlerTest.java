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
import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;
import ru.agimate.controlapi.service.tool.AgentToolCallService;
import ru.agimate.controlapi.service.channel.handler.dto.ChannelConfig;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundDispatch;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.ToolDefinition;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("TelegramChannelHandler")
class TelegramChannelHandlerTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String IDENTITY = "bot-creds-1";

    @Mock
    private AgentToolCallService toolCallService;

    private TelegramChannelHandler handler;
    private ChannelConfig config;

    @BeforeEach
    void setUp() {
        handler = new TelegramChannelHandler();
        config = new ChannelConfig(AGENT_ID, "telegram", IDENTITY, Map.of());
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
                    .anyMatch(t -> t.triggerName().equals("message_received")));
        }

        @Test
        @DisplayName("reply tool is send_message bound to channel connectionId")
        void tools() {
            ToolDefinition tool = handler.listOfTools(config).get(0);
            assertEquals(IDENTITY, tool.connectionId());
            assertEquals("send_message", tool.toolName());
        }

        @Test
        @DisplayName("validateConfig rejects non-telegram connector")
        void validateConfigRejectsWrongConnector() {
            ChannelConfig wrong = new ChannelConfig(AGENT_ID, "slack", IDENTITY, Map.of());
            assertThrows(ConnectorException.class, () -> handler.validateConfig(wrong));
        }

        @Test
        @DisplayName("getConfigFields exposes allowedChatIds as a JSON schema property")
        void configFields() {
            Map<String, Object> schema = handler.getConfigFields();
            assertEquals("object", schema.get("type"));
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) schema.get("properties");
            assertTrue(props.containsKey("allowedChatIds"));
        }
    }

    @Nested
    @DisplayName("handleInput")
    class Convert {

        @Test
        @DisplayName("plain text message")
        void message() {
            String text = handler.handleInput(config,
                    trigger("message_received", Map.of("chatId", 42, "text", "Привет"))).orElseThrow().text();
            assertEquals("Привет", text);
        }

        @Test
        @DisplayName("command keeps the full command line")
        void command() {
            String text = handler.handleInput(config,
                    trigger("command_received", Map.of("chatId", 42, "text", "/start now"))).orElseThrow().text();
            assertEquals("/start now", text);
        }

        @Test
        @DisplayName("callback query is described")
        void callback() {
            String text = handler.handleInput(config,
                    trigger("callback_query", Map.of("chatId", 42, "data", "yes"))).orElseThrow().text();
            assertEquals("[Нажата кнопка] yes", text);
        }

        @Test
        @DisplayName("photo without caption is described, no file fetched")
        void photo() {
            String text = handler.handleInput(config,
                    trigger("photo_received", Map.of("chatId", 42))).orElseThrow().text();
            assertEquals("[Пользователь отправил изображение]", text);
        }

        @Test
        @DisplayName("photo caption is appended")
        void photoWithCaption() {
            String text = handler.handleInput(config,
                    trigger("photo_received", Map.of("chatId", 42, "caption", "смотри"))).orElseThrow().text();
            assertEquals("[Пользователь отправил изображение] смотри", text);
        }

        @Test
        @DisplayName("document uses its file name")
        void document() {
            String text = handler.handleInput(config,
                    trigger("document_received",
                            Map.of("chatId", 42, "document", Map.of("file_name", "report.pdf")))).orElseThrow().text();
            assertEquals("[Пользователь отправил документ: report.pdf]", text);
        }
    }

    @Nested
    @DisplayName("chat filter (allowedChatIds)")
    class ChatFilter {

        @Test
        @DisplayName("allows chats in the list")
        void allowed() {
            ChannelConfig filtered = new ChannelConfig(AGENT_ID, "telegram", IDENTITY, Map.of("allowedChatIds", List.of(42)));
            assertTrue(handler.handleInput(filtered,
                    trigger("message_received", Map.of("chatId", 42, "text", "hi"))).isPresent());
        }

        @Test
        @DisplayName("filters out chats not in the list")
        void filteredOut() {
            ChannelConfig filtered = new ChannelConfig(AGENT_ID, "telegram", IDENTITY, Map.of("allowedChatIds", List.of(42)));
            assertTrue(handler.handleInput(filtered,
                    trigger("message_received", Map.of("chatId", 999, "text", "hi"))).isEmpty());
        }

        @Test
        @DisplayName("empty list means all chats allowed")
        void emptyAllowsAll() {
            assertTrue(handler.handleInput(config,
                    trigger("message_received", Map.of("chatId", 999, "text", "hi"))).isPresent());
        }
    }

    @Nested
    @DisplayName("handleOutput")
    class Process {

        @Test
        @DisplayName("calls processToolCall with send_message and chatId from reply context")
        void dispatches() {
            OutboundMessage outbound = OutboundMessage.text("Готово");
            OutboundDispatch dispatch = new OutboundDispatch("call-1", null, null, null, null, Map.of("chatId", 42));

            handler.handleOutput(config, outbound, dispatch, toolCallService);

            ArgumentCaptor<ToolCallRequest> req = ArgumentCaptor.forClass(ToolCallRequest.class);
            verify(toolCallService).processToolCall(eq(AGENT_ID), req.capture());
            ToolCallRequest r = req.getValue();
            assertEquals("telegram", r.getConnectorCode());
            assertEquals(IDENTITY, r.getConnectionId());
            assertEquals("send_message", r.getName());
            assertEquals("call-1", r.getId());
            assertEquals("42", r.getInput().get("chatId"));
            assertEquals("Готово", r.getInput().get("text"));
        }

        @Test
        @DisplayName("falls back to config defaultChatId when reply context has none")
        void defaultChatIdFallback() {
            ChannelConfig withDefault = new ChannelConfig(AGENT_ID, "telegram", IDENTITY, Map.of("defaultChatId", 777));
            OutboundMessage outbound = OutboundMessage.text("Напоминание");
            OutboundDispatch dispatch = new OutboundDispatch("call-2", null, null, null, null, Map.of());

            handler.handleOutput(withDefault, outbound, dispatch, toolCallService);

            ArgumentCaptor<ToolCallRequest> req = ArgumentCaptor.forClass(ToolCallRequest.class);
            verify(toolCallService).processToolCall(eq(AGENT_ID), req.capture());
            assertEquals("777", req.getValue().getInput().get("chatId"));
        }

        @Test
        @DisplayName("throws when chatId is missing and no defaultChatId")
        void missingChatId() {
            OutboundMessage outbound = OutboundMessage.text("Готово");
            OutboundDispatch dispatch = new OutboundDispatch("call-1", null, null, null, null, Map.of());

            assertThrows(ConnectorException.class, () -> handler.handleOutput(config, outbound, dispatch, toolCallService));
            verifyNoInteractions(toolCallService);
        }
    }
}
