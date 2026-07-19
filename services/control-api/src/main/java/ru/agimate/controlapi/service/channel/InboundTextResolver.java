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
 * Каноническое inbound-сообщение канала: тот же {@link ChannelHandler#handleInput}, что и при
 * dispatch триггера — детерминированная функция от персистентных данных (конфиг канала +
 * {@code trigger_log.input}). Общий для сборки контекста ({@code RunContextService}) и записи
 * истории ({@code MessageLogService}).
 *
 * <p>{@link #resolve} возвращает полное {@link InboundMessage} (текст + вложения) — вложения нужны
 * контексту рана (мультимодальность); {@link #resolveText} — text-only обёртка для записи истории.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboundTextResolver {

    private final ChannelRepository channelRepository;
    private final ChannelHandlerRegistry channelHandlerRegistry;

    /** Полное inbound-сообщение (текст + parts); {@code empty} — канал/handler исчезли. */
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

    /** Каноничный текст inbound: непустой текст извлечённого сообщения, иначе {@code empty}. */
    public Optional<String> resolveText(UUID promptChannelId, Trigger trigger) {
        return resolve(promptChannelId, trigger)
                .map(InboundMessage::text)
                .filter(text -> text != null && !text.isBlank());
    }
}
