package ru.agimate.controlapi.service.channel.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.repositories.ChannelRepository;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChannelMessageLogResolver")
class ChannelMessageLogResolverTest {

    private static final UUID CHANNEL_ID = UUID.randomUUID();

    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private ChannelHandlerRegistry channelHandlerRegistry;
    @Mock
    private ChannelMessageLog channelMessageLog;

    private ChannelMessageLogResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ChannelMessageLogResolver(channelRepository, channelHandlerRegistry);
    }

    @Test
    @DisplayName("капабилити ищется по channel_handler канала, а не по коду коннектора")
    void resolvesByHandlerName() {
        AgentSession session = AgentSession.builder().channelId(CHANNEL_ID).connectorCode("webchat").build();
        when(channelRepository.findById(CHANNEL_ID))
                .thenReturn(Optional.of(Channel.builder().channelHandler("webchat").build()));
        when(channelHandlerRegistry.findCapability("webchat", ChannelMessageLog.class))
                .thenReturn(Optional.of(channelMessageLog));

        assertEquals(Optional.of(channelMessageLog), resolver.find(session));
    }

    @Test
    @DisplayName("канал без своего лога — пусто, отвечает общая проекция")
    void emptyWithoutCapability() {
        AgentSession session = AgentSession.builder().channelId(CHANNEL_ID).connectorCode("telegram").build();
        when(channelRepository.findById(CHANNEL_ID))
                .thenReturn(Optional.of(Channel.builder().channelHandler("telegram").build()));
        when(channelHandlerRegistry.findCapability("telegram", ChannelMessageLog.class))
                .thenReturn(Optional.empty());

        assertTrue(resolver.find(session).isEmpty());
    }

    @Test
    @DisplayName("у сессии без канала своего лога нет — отвечает общая проекция")
    void sessionWithoutChannel() {
        AgentSession session = AgentSession.builder().channelId(null).build();

        assertTrue(resolver.find(session).isEmpty());
    }

    @Test
    @DisplayName("канал уже удалён — запись осталась, история читается")
    void deletedChannelStillResolves() {
        AgentSession session = AgentSession.builder().channelId(CHANNEL_ID).build();
        when(channelRepository.findById(CHANNEL_ID))
                .thenReturn(Optional.of(Channel.builder().channelHandler("webchat").build()));
        when(channelHandlerRegistry.findCapability("webchat", ChannelMessageLog.class))
                .thenReturn(Optional.of(channelMessageLog));

        assertEquals(Optional.of(channelMessageLog), resolver.find(session));
    }
}
