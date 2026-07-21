package ru.agimate.controlapi.service.trigger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.ChannelSession;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.service.channel.ChannelSessionService;
import ru.agimate.controlapi.service.channel.handler.ChannelHandler;
import ru.agimate.controlapi.service.channel.handler.ChannelHandlerRegistry;
import ru.agimate.controlapi.service.channel.handler.dto.ChannelConfig;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChannelRouteResolver")
class ChannelRouteResolverTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID CHANNEL_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final String IDENTITY = CONNECTION_ID.toString();

    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private ChannelSessionService channelSessionService;
    @Mock
    private ChannelHandlerRegistry channelHandlerRegistry;
    @Mock
    private ChannelHandler handler;

    private ChannelRouteResolver resolver;
    private Agent agent;
    private Channel channel;

    @BeforeEach
    void setUp() {
        resolver = new ChannelRouteResolver(channelRepository, channelSessionService, channelHandlerRegistry);
        agent = Agent.builder().id(AGENT_ID).build();
        channel = Channel.builder()
                .id(CHANNEL_ID)
                .agentId(AGENT_ID)
                .channelHandler("webchat")
                .connectorCode("webchat")
                .connectionId(CONNECTION_ID)
                .build();
    }

    private void stubChannelLookupByTriple() {
        when(channelRepository.findByAgentIdAndConnectorCodeAndConnectionIdAndDeletedAtIsNull(
                AGENT_ID, "webchat", CONNECTION_ID)).thenReturn(Optional.of(channel));
        when(channelHandlerRegistry.find("webchat")).thenReturn(Optional.of(handler));
        when(handler.handleInput(any(ChannelConfig.class), any(Trigger.class)))
                .thenReturn(Optional.of(InboundMessage.text("hi")));
    }

    private static ChannelSession session(UUID id, UUID channelId) {
        ChannelSession s = ChannelSession.builder().channelId(channelId).build();
        s.setId(id);
        return s;
    }

    @Nested
    @DisplayName("declared sessionId в prompt-ChannelInfo")
    class DeclaredSession {

        private final UUID declaredSessionId = UUID.randomUUID();

        private Trigger triggerWithDeclaredSession() {
            return Trigger.createDirected("webchat", IDENTITY, "message_received",
                    Map.of("text", "hi"),
                    new TriggerContext(null,
                            Channels.ofPrompt(new ChannelInfo(CHANNEL_ID, declaredSessionId, null))));
        }

        @Test
        @DisplayName("открытая объявленная сессия используется вместо findOrCreateActive")
        void declaredSessionUsed() {
            when(channelRepository.findById(CHANNEL_ID)).thenReturn(Optional.of(channel));
            when(channelHandlerRegistry.find("webchat")).thenReturn(Optional.of(handler));
            when(handler.handleInput(any(ChannelConfig.class), any(Trigger.class)))
                    .thenReturn(Optional.of(InboundMessage.text("hi")));
            when(channelSessionService.findOpen(declaredSessionId, CHANNEL_ID))
                    .thenReturn(Optional.of(session(declaredSessionId, CHANNEL_ID)));

            ChannelResolution resolution = resolver.resolve(agent, triggerWithDeclaredSession());

            assertEquals(ChannelResolution.Kind.CHANNEL, resolution.kind());
            assertEquals(declaredSessionId, resolution.channels().prompt().sessionId());
            verify(channelSessionService, never()).findOrCreateActive(any(), any());
        }

        @Test
        @DisplayName("закрытая/чужая объявленная сессия — фолбэк на findOrCreateActive")
        void declaredSessionInvalidFallsBack() {
            UUID fallbackSessionId = UUID.randomUUID();
            when(channelRepository.findById(CHANNEL_ID)).thenReturn(Optional.of(channel));
            when(channelHandlerRegistry.find("webchat")).thenReturn(Optional.of(handler));
            when(handler.handleInput(any(ChannelConfig.class), any(Trigger.class)))
                    .thenReturn(Optional.of(InboundMessage.text("hi")));
            when(channelSessionService.findOpen(declaredSessionId, CHANNEL_ID)).thenReturn(Optional.empty());
            when(channelSessionService.findOrCreateActive(channel, null))
                    .thenReturn(session(fallbackSessionId, CHANNEL_ID));

            ChannelResolution resolution = resolver.resolve(agent, triggerWithDeclaredSession());

            assertEquals(fallbackSessionId, resolution.channels().prompt().sessionId());
        }
    }

    @Nested
    @DisplayName("проактивные каналы (progress/answer без prompt)")
    class ProactiveChannels {

        private final UUID snapshotSessionId = UUID.randomUUID();

        private Trigger proactiveTrigger(UUID sessionId) {
            ChannelInfo ref = new ChannelInfo(CHANNEL_ID, sessionId, null);
            return Trigger.createDirected("time", IDENTITY, "due", Map.of("prompt", "п"),
                    new TriggerContext(null, new Channels(null, ref, ref)));
        }

        @Test
        @DisplayName("открытая снапшот-сессия используется как есть")
        void openSnapshotSessionKept() {
            when(channelRepository.findById(CHANNEL_ID)).thenReturn(Optional.of(channel));
            when(channelSessionService.findOpen(snapshotSessionId, CHANNEL_ID))
                    .thenReturn(Optional.of(session(snapshotSessionId, CHANNEL_ID)));

            ChannelResolution resolution = resolver.resolve(agent, proactiveTrigger(snapshotSessionId));

            assertEquals(ChannelResolution.Kind.CHANNEL, resolution.kind());
            assertNull(resolution.message());
            assertNull(resolution.channels().prompt());
            assertEquals(snapshotSessionId, resolution.channels().progress().sessionId());
            assertEquals(snapshotSessionId, resolution.channels().answer().sessionId());
            verify(channelSessionService, never()).findOrCreateActive(any(), any());
        }

        @Test
        @DisplayName("закрытая снапшот-сессия — фолбэк на активную сессию канала")
        void closedSnapshotFallsBackToActive() {
            UUID activeSessionId = UUID.randomUUID();
            when(channelRepository.findById(CHANNEL_ID)).thenReturn(Optional.of(channel));
            when(channelSessionService.findOpen(snapshotSessionId, CHANNEL_ID))
                    .thenReturn(Optional.empty());
            when(channelSessionService.findOrCreateActive(channel, null))
                    .thenReturn(session(activeSessionId, CHANNEL_ID));

            ChannelResolution resolution = resolver.resolve(agent, proactiveTrigger(snapshotSessionId));

            assertEquals(activeSessionId, resolution.channels().progress().sessionId());
            assertEquals(activeSessionId, resolution.channels().answer().sessionId());
        }

        @Test
        @DisplayName("снапшота сессии нет — активная сессия канала")
        void noSnapshotResolvesActive() {
            UUID activeSessionId = UUID.randomUUID();
            when(channelRepository.findById(CHANNEL_ID)).thenReturn(Optional.of(channel));
            when(channelSessionService.findOrCreateActive(channel, null))
                    .thenReturn(session(activeSessionId, CHANNEL_ID));

            ChannelResolution resolution = resolver.resolve(agent, proactiveTrigger(null));

            assertEquals(activeSessionId, resolution.channels().answer().sessionId());
        }

        @Test
        @DisplayName("канал принадлежит другому агенту — прямая доставка")
        void foreignChannelFallsBackToDirect() {
            Channel foreign = Channel.builder()
                    .id(CHANNEL_ID)
                    .agentId(UUID.randomUUID())
                    .channelHandler("webchat")
                    .connectorCode("webchat")
                    .connectionId(CONNECTION_ID)
                    .build();
            when(channelRepository.findById(CHANNEL_ID)).thenReturn(Optional.of(foreign));

            ChannelResolution resolution = resolver.resolve(agent, proactiveTrigger(snapshotSessionId));

            assertEquals(ChannelResolution.Kind.DIRECT, resolution.kind());
        }
    }

    @Nested
    @DisplayName("progress-роль по ChannelHandler.deliverProgress")
    class ProgressRole {

        private final Trigger trigger = Trigger.createBasic("webchat", IDENTITY, "message_received",
                Map.of("text", "hi"));

        @Test
        @DisplayName("deliverProgress=true — progress заполняется тем же каналом/сессией")
        void progressFilled() {
            stubChannelLookupByTriple();
            UUID sessionId = UUID.randomUUID();
            when(handler.deliverProgress(any(ChannelConfig.class))).thenReturn(true);
            when(channelSessionService.findOrCreateActive(channel, null))
                    .thenReturn(session(sessionId, CHANNEL_ID));

            ChannelResolution resolution = resolver.resolve(agent, trigger);

            assertEquals(resolution.channels().prompt(), resolution.channels().progress());
            assertEquals(sessionId, resolution.channels().progress().sessionId());
            assertNull(resolution.channels().answer());
        }

        @Test
        @DisplayName("deliverProgress=false (дефолт) — только prompt")
        void progressAbsent() {
            stubChannelLookupByTriple();
            when(handler.deliverProgress(any(ChannelConfig.class))).thenReturn(false);
            when(channelSessionService.findOrCreateActive(channel, null))
                    .thenReturn(session(UUID.randomUUID(), CHANNEL_ID));

            ChannelResolution resolution = resolver.resolve(agent, trigger);

            assertNull(resolution.channels().progress());
            assertNull(resolution.channels().answer());
        }
    }
}
