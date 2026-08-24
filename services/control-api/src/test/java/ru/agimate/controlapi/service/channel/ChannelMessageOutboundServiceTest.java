package ru.agimate.controlapi.service.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.enums.FileReferenceKind;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.service.session.AgentSessionService;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.service.channel.handler.ChannelHandler;
import ru.agimate.controlapi.service.channel.handler.ChannelHandlerRegistry;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.Part;
import ru.agimate.controlapi.service.tool.AgentToolCallService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    @Mock private AgentSessionRepository agentSessionRepository;
    @Mock private ChannelSessionMessageRepository channelSessionMessageRepository;
    @Mock private AgentSessionService agentSessionService;
    @Mock private ChannelHandlerRegistry channelHandlerRegistry;
    @Mock private AgentToolCallService agentToolCallService;
    @Mock private OutboundAttachmentParser attachmentParser;
    @Mock private ru.agimate.controlapi.service.file.FileReferenceService fileReferenceService;
    @Mock private ChannelHandler handler;

    @InjectMocks private ChannelMessageOutboundService service;

    private Channel channel;
    private AgentSession session;

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
        session = AgentSession.builder().id(SESSION_ID).channelId(CHANNEL_ID).build();
    }

    private void stubHappyPath(OutboundMessage outbound, List<ToolCallRequest> requests) {
        when(channelRepository.findByIdAndDeletedAtIsNull(CHANNEL_ID)).thenReturn(Optional.of(channel));
        when(channelHandlerRegistry.find("telegram")).thenReturn(Optional.of(handler));
        when(agentSessionService.findOrCreateActive(channel, null)).thenReturn(session);
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
        @DisplayName("parts доставляются только в answer-стрим: progress получает текст без вложений")
        void progressStreamDropsParts() {
            OutboundMessage outbound = OutboundMessage.text("думаю про [[attach:agf_x]]");
            OutboundMessage parsed = new OutboundMessage("думаю про",
                    List.of(new Part("image", "agf_" + UUID.randomUUID(), "image/png", 5, Map.of())));
            when(channelRepository.findByIdAndDeletedAtIsNull(CHANNEL_ID)).thenReturn(Optional.of(channel));
            when(channelHandlerRegistry.find("telegram")).thenReturn(Optional.of(handler));
            when(agentSessionService.findOrCreateActive(channel, null)).thenReturn(session);
            when(channelSessionMessageRepository
                    .findFirstBySessionIdAndTriggerInputIsNotNullOrderByCreatedAtDesc(SESSION_ID))
                    .thenReturn(Optional.empty());
            when(attachmentParser.parse(USER_ID, outbound)).thenReturn(parsed);
            when(handler.supportsOutboundAttachments()).thenReturn(true);
            when(handler.handleOutput(any(), any(), any())).thenReturn(List.of());

            service.send(AGENT_ID, CHANNEL_ID, null, outbound, "m1", "progress", "THINKING");

            ArgumentCaptor<OutboundMessage> delivered = ArgumentCaptor.forClass(OutboundMessage.class);
            verify(handler).handleOutput(any(), delivered.capture(), any());
            assertEquals("думаю про", delivered.getValue().text());
            assertTrue(delivered.getValue().parts().isEmpty());
            // Вложение не доставлено — значит и ссылки на разговор быть не должно.
            verify(fileReferenceService).record(eq(List.of()), eq(SESSION_ID), eq(AGENT_ID),
                    eq(FileReferenceKind.OUTBOUND));
        }

        @Test
        @DisplayName("доставленное вложение записывается в разговор — это воронка всего исходящего")
        void recordsDeliveredAttachments() {
            String fileId = "agf_" + UUID.randomUUID();
            OutboundMessage outbound = OutboundMessage.text("держи [[attach:" + fileId + "]]");
            OutboundMessage parsed = new OutboundMessage("держи",
                    List.of(new Part("image", fileId, "image/png", 5, Map.of())));
            when(channelRepository.findByIdAndDeletedAtIsNull(CHANNEL_ID)).thenReturn(Optional.of(channel));
            when(channelHandlerRegistry.find("telegram")).thenReturn(Optional.of(handler));
            when(agentSessionService.findOrCreateActive(channel, null)).thenReturn(session);
            when(channelSessionMessageRepository
                    .findFirstBySessionIdAndTriggerInputIsNotNullOrderByCreatedAtDesc(SESSION_ID))
                    .thenReturn(Optional.empty());
            when(attachmentParser.parse(USER_ID, outbound)).thenReturn(parsed);
            when(handler.supportsOutboundAttachments()).thenReturn(true);
            when(handler.handleOutput(any(), any(), any())).thenReturn(List.of());

            service.send(AGENT_ID, CHANNEL_ID, null, outbound, "m1", null, null);

            verify(fileReferenceService).record(eq(List.of(fileId)), eq(SESSION_ID), eq(AGENT_ID),
                    eq(FileReferenceKind.OUTBOUND));
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
