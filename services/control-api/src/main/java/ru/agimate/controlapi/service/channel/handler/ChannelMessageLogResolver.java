package ru.agimate.controlapi.service.channel.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.repositories.ChannelRepository;

import java.util.Optional;

/**
 * Resolves the message-log capability of a session's channel. The lookup goes through
 * {@code channels.channel_handler}, not the connector code: {@code generic} serves dynamic
 * connectors, whose codes differ from the handler name, so the session's {@code connectorCode} is
 * not the key.
 *
 * <p>Empty — the channel has no store of its own, and the platform's generic projection
 * ({@code channel_session_messages}) answers. See docs/decisions/message-stores.md.
 */
@Component
@RequiredArgsConstructor
public class ChannelMessageLogResolver {

    private final ChannelRepository channelRepository;
    private final ChannelHandlerRegistry channelHandlerRegistry;

    /** The channel's own message log; a soft-deleted channel still resolves — its history stays readable. */
    public Optional<ChannelMessageLog> find(AgentSession session) {
        if (session.getChannelId() == null) {
            // A connection-scope session has no channel: nothing can own its log, the projection answers.
            return Optional.empty();
        }
        return channelRepository.findById(session.getChannelId())
                .flatMap(channel -> channelHandlerRegistry.findCapability(
                        channel.getChannelHandler(), ChannelMessageLog.class));
    }
}
