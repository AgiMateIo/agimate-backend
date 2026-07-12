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
import ru.agimate.controlapi.service.acp.AcpSessionRegistry;
import ru.agimate.controlapi.service.tool.AgentToolCallService;
import ru.agimate.controlapi.service.channel.handler.dto.ChannelConfig;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundDispatch;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("AcpChannelHandler")
class AcpChannelHandlerTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID CHANNEL_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final String IDENTITY = UUID.randomUUID().toString();

    @Mock
    private AcpSessionRegistry sessionRegistry;
    @Mock
    private AgentToolCallService toolCallService;

    private AcpChannelHandler handler;
    private ChannelConfig config;

    @BeforeEach
    void setUp() {
        handler = new AcpChannelHandler(sessionRegistry);
        config = new ChannelConfig(AGENT_ID, "acp", IDENTITY, Map.of());
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
            ChannelConfig wrong = new ChannelConfig(AGENT_ID, "webchat", IDENTITY, Map.of());
            assertThrows(ConnectorException.class, () -> handler.validateConfig(wrong));
        }
    }

    @Nested
    @DisplayName("handleInput")
    class Input {

        @Test
        @DisplayName("извлекает text из trigger.data; пустой — фильтр")
        void extractsText() {
            Trigger trigger = Trigger.createBasic("acp", IDENTITY, "message_received",
                    Map.of("sessionId", SESSION_ID.toString(), "text", "привет"));
            Optional<InboundMessage> inbound = handler.handleInput(config, trigger);
            assertEquals("привет", inbound.orElseThrow().text());

            Trigger blank = Trigger.createBasic("acp", IDENTITY, "message_received", Map.of("text", " "));
            assertTrue(handler.handleInput(config, blank).isEmpty());
        }
    }

    @Nested
    @DisplayName("handleOutput → session/update")
    class Output {

        @SuppressWarnings("unchecked")
        private Map<String, Object> capturedUpdate() {
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(sessionRegistry).sendUpdate(eq(SESSION_ID), captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("progress THINKING → agent_thought_chunk")
        void thinking() {
            OutboundDispatch dispatch = dispatch("progress", "THINKING");
            handler.handleOutput(config, OutboundMessage.text("думаю"), dispatch, toolCallService);

            Map<String, Object> update = capturedUpdate();
            assertEquals("agent_thought_chunk", update.get("sessionUpdate"));
            assertEquals(Map.of("type", "text", "text", "думаю"), update.get("content"));
            verify(sessionRegistry, never()).completePrompt(eq(SESSION_ID), eq(AcpSessionRegistry.STOP_END_TURN));
        }

        @Test
        @DisplayName("progress TOOL_CALL → tool_call со статусом completed")
        void toolCall() {
            OutboundDispatch dispatch = dispatch("progress", "TOOL_CALL");
            handler.handleOutput(config, OutboundMessage.text("🔧 search"), dispatch, toolCallService);

            Map<String, Object> update = capturedUpdate();
            assertEquals("tool_call", update.get("sessionUpdate"));
            assertEquals("msg-1", update.get("toolCallId"));
            assertEquals("🔧 search", update.get("title"));
            assertEquals("completed", update.get("status"));
        }

        @Test
        @DisplayName("progress TEXT → agent_message_chunk")
        void progressText() {
            OutboundDispatch dispatch = dispatch("progress", "TEXT");
            handler.handleOutput(config, OutboundMessage.text("сейчас проверю"), dispatch, toolCallService);

            assertEquals("agent_message_chunk", capturedUpdate().get("sessionUpdate"));
        }

        @Test
        @DisplayName("answer (и null-stream) → agent_message_chunk + завершение prompt")
        void answerCompletesPrompt() {
            OutboundDispatch dispatch = dispatch(null, null);
            handler.handleOutput(config, OutboundMessage.text("готово"), dispatch, toolCallService);

            assertEquals("agent_message_chunk", capturedUpdate().get("sessionUpdate"));
            verify(sessionRegistry).completePrompt(SESSION_ID, AcpSessionRegistry.STOP_END_TURN);
        }

        @Test
        @DisplayName("error → failPrompt, update не шлётся")
        void errorFailsPrompt() {
            OutboundDispatch dispatch = dispatch("error", null);
            handler.handleOutput(config, OutboundMessage.text("boom"), dispatch, toolCallService);

            verify(sessionRegistry).failPrompt(SESSION_ID, AcpChannelHandler.AGENT_ERROR_CODE, "boom");
            verify(sessionRegistry, never()).sendUpdate(eq(SESSION_ID), eq(Map.of()));
            verifyNoInteractions(toolCallService);
        }

        private OutboundDispatch dispatch(String stream, String progressType) {
            return new OutboundDispatch("msg-1", stream, progressType, CHANNEL_ID, SESSION_ID, Map.of());
        }
    }
}
