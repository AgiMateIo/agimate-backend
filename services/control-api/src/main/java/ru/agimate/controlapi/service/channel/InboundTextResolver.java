package ru.agimate.controlapi.service.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.service.channel.handler.ChannelHandler;
import ru.agimate.controlapi.service.channel.handler.ChannelHandlerRegistry;
import ru.agimate.controlapi.service.channel.handler.dto.ChannelConfig;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.Optional;
import java.util.UUID;

/**
 * The canonical inbound message of a channel: the same {@link ChannelHandler#handleInput} as during
 * trigger dispatch — a deterministic function of persistent data (the channel's config plus
 * {@code trigger_log.input}). Shared by context assembly ({@code RunContextService}) and history
 * writing ({@code MessageLogService}).
 *
 * <p>{@link #resolve} returns the full {@link InboundMessage} (text plus attachments) — the run's
 * context needs the attachments (multimodality); {@link #resolveText} is the text-only wrapper for
 * writing history.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboundTextResolver {

    private final ChannelRepository channelRepository;
    private final ChannelHandlerRegistry channelHandlerRegistry;

    /** The full inbound message (text plus parts); {@code empty} — the channel or handler is gone. */
    public Optional<InboundMessage> resolve(UUID promptChannelId, Trigger trigger) {
        Channel channel = channelRepository.findById(promptChannelId)
                .filter(c -> c.getDeletedAt() == null)
                .orElse(null);
        if (channel == null) {
            log.warn("Prompt channel {} missing for trigger {}", promptChannelId, trigger.id());
            return Optional.empty();
        }
        ChannelHandler handler = channelHandlerRegistry.find(channel.getChannelHandler()).orElse(null);
        if (handler == null) {
            log.warn("No handler '{}' for channel {}", channel.getChannelHandler(), channel.getId());
            return Optional.empty();
        }
        ChannelConfig config = new ChannelConfig(channel.getAgentId(), channel.getConnectorCode(),
                channel.getConnectionId().toString(), channel.getConfig());
        return handler.handleInput(config, trigger);
    }

    /** The canonical inbound text: the non-empty text of the extracted message, otherwise {@code empty}. */
    public Optional<String> resolveText(UUID promptChannelId, Trigger trigger) {
        return resolve(promptChannelId, trigger)
                .map(InboundMessage::text)
                .filter(text -> text != null && !text.isBlank());
    }
}
