package ru.agimate.controlapi.service.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.ChannelSession;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.database.repositories.ChannelSessionRepository;
import ru.agimate.controlapi.service.channel.handler.ChannelHandler;
import ru.agimate.controlapi.service.channel.handler.ChannelHandlerRegistry;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;
import ru.agimate.controlapi.service.tool.AgentToolCallService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChannelMessageOutboundService")
class ChannelMessageOutboundServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CHANNEL_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();

    @Mock private ChannelRepository channelRepository;
    @Mock private ChannelSessionRepository channelSessionRepository;
    @Mock private ChannelSessionMessageRepository channelSessionMessageRepository;
    @Mock private ChannelSessionService channelSessionService;
    @Mock private ChannelHandlerRegistry channelHandlerRegistry;
    @Mock private AgentToolCallService agentToolCallService;
    @Mock private OutboundAttachmentParser attachmentParser;
    @Mock private ChannelHandler handler;

    @InjectMocks private ChannelMessageOutboundService service;

    private Channel channel;
    private ChannelSession session;

    @BeforeEach
    void setUp() {
        channel = Channel.builder()
                .id(CHANNEL_ID)
                .userId(USER_ID)
                .agentId(AGENT_ID)
                .name("tg")
                .channelHandler("telegram")
                .connectorCode("telegram")
                .connectionId(CONNECTION_ID)
                .config(Map.of())
                .build();
        session = ChannelSession.builder().id(SESSION_ID).channelId(CHANNEL_ID).build();
    }

    private void stubHappyPath(OutboundMessage outbound, List<ToolCallRequest> requests) {
        when(channelRepository.findByIdAndDeletedAtIsNull(CHANNEL_ID)).thenReturn(Optional.of(channel));
        when(channelHandlerRegistry.find("telegram")).thenReturn(Optional.of(handler));
        when(channelSessionService.findOrCreateActive(channel, null)).thenReturn(session);
        when(channelSessionMessageRepository
                .findFirstBySessionIdAndTriggerInputIsNotNullOrderByCreatedAtDesc(SESSION_ID))
                .thenReturn(Optional.empty());
        when(attachmentParser.parse(USER_ID, outbound)).thenReturn(outbound);
        when(handler.handleOutput(any(), eq(outbound), any())).thenReturn(requests);
    }

    private static ToolCallRequest request(String id) {
        return ToolCallRequest.builder()
                .id(id)
                .connectorCode("telegram")
                .connectionId(CONNECTION_ID.toString())
                .name("send_message")
                .input(Map.of("chatId", "42"))
                .build();
    }

    @Nested
    @DisplayName("dispatch isolation")
    class DispatchIsolation {

        @Test
        @DisplayName("dispatches every request and returns messageId when all succeed")
        void allSucceed() {
            OutboundMessage outbound = OutboundMessage.text("hi");
            stubHappyPath(outbound, List.of(request("m1"), request("m1:att0")));

            var result = service.send(AGENT_ID, CHANNEL_ID, null, outbound, "m1", null, null);

            assertEquals("m1", result.messageId());
            verify(agentToolCallService, times(2)).processToolCall(eq(AGENT_ID), any());
        }

        @Test
        @DisplayName("failure of one request does not stop the remaining ones")
        void oneFailureIsIsolated() {
            OutboundMessage outbound = OutboundMessage.text("hi");
            ToolCallRequest text = request("m1");
            ToolCallRequest att0 = request("m1:att0");
            ToolCallRequest att1 = request("m1:att1");
            stubHappyPath(outbound, List.of(text, att0, att1));
            doThrow(new ForbiddenStatusException("denied"))
                    .when(agentToolCallService).processToolCall(AGENT_ID, att0);

            var result = service.send(AGENT_ID, CHANNEL_ID, null, outbound, "m1", null, null);

            assertEquals("m1", result.messageId());
            verify(agentToolCallService).processToolCall(AGENT_ID, text);
            verify(agentToolCallService).processToolCall(AGENT_ID, att1);
        }

        @Test
        @DisplayName("rethrows the first failure when every request fails")
        void allFailedPropagates() {
            OutboundMessage outbound = OutboundMessage.text("hi");
            ToolCallRequest text = request("m1");
            ToolCallRequest att0 = request("m1:att0");
            stubHappyPath(outbound, List.of(text, att0));
            doThrow(new ForbiddenStatusException("denied"))
                    .when(agentToolCallService).processToolCall(eq(AGENT_ID), any());

            assertThrows(ForbiddenStatusException.class,
                    () -> service.send(AGENT_ID, CHANNEL_ID, null, outbound, "m1", null, null));
            verify(agentToolCallService).processToolCall(AGENT_ID, text);
            verify(agentToolCallService).processToolCall(AGENT_ID, att0);
        }
    }
}
