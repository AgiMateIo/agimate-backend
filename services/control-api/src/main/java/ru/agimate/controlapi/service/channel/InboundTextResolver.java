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
 * Канонический текст inbound-сообщения канала: тот же {@link ChannelHandler#handleInput}, что и
 * при dispatch триггера — детерминированная функция от персистентных данных (конфиг канала +
 * {@code trigger_log.input}). Общий для сборки контекста ({@code RunContextService}) и записи
 * истории ({@code MessageLogService}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboundTextResolver {

    private final ChannelRepository channelRepository;
    private final ChannelHandlerRegistry channelHandlerRegistry;

    public Optional<String> resolve(UUID promptChannelId, Trigger trigger) {
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
        return handler.handleInput(config, trigger)
                .map(InboundMessage::text)
                .filter(text -> text != null && !text.isBlank());
    }
}
