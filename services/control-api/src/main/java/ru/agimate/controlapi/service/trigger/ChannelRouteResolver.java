package ru.agimate.controlapi.service.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.ChannelSession;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.service.channel.ChannelSessionService;
import ru.agimate.controlapi.service.channel.handler.ChannelHandler;
import ru.agimate.controlapi.service.channel.handler.ChannelHandlerRegistry;
import ru.agimate.controlapi.service.channel.handler.dto.ChannelConfig;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;

import java.util.Optional;
import java.util.UUID;

/**
 * «Как»-слой роутинга: для уже отобранного получателя решает, как строится взаимодействие —
 * канал (заданный в триггере либо активный для {@code (agent, connector, identity)}) или прямая
 * доставка. Policy/audience («кто») сюда не входят.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelRouteResolver {

    private final ChannelRepository channelRepository;
    private final ChannelSessionService channelSessionService;
    private final ChannelHandlerRegistry channelHandlerRegistry;

    ChannelResolution resolve(Agent agent, Trigger trigger) {
        Channel channel = resolveChannel(agent, trigger);
        if (channel == null) {
            return ChannelResolution.direct();
        }
        return resolveInbound(channel, trigger);
    }

    /**
     * Канал для агента: если продюсер задал prompt-канал в {@link TriggerContext} (declared) — берём его,
     * но только для агента-владельца канала; иначе резолвим per-agent по тройке {@code (agent, connector, identity)}.
     */
    private Channel resolveChannel(Agent agent, Trigger trigger) {
        UUID declaredChannelId = declaredPromptChannelId(trigger);
        if (declaredChannelId != null) {
            Channel declared = channelRepository.findById(declaredChannelId)
                    .filter(c -> c.getDeletedAt() == null)
                    .orElse(null);
            if (declared == null || !declared.getAgentId().equals(agent.getId())) {
                log.debug("Declared channel {} not applicable to agent {} - direct route",
                        declaredChannelId, agent.getId());
                return null;
            }
            return declared;
        }
        return channelRepository.findByAgentIdAndConnectorCodeAndIdentityAndDeletedAtIsNull(
                agent.getId(), trigger.connectorCode(), trigger.identity()).orElse(null);
    }

    private ChannelResolution resolveInbound(Channel channel, Trigger trigger) {
        ChannelHandler handler = channelHandlerRegistry.find(channel.getChannelHandler()).orElse(null);
        if (handler == null) {
            log.warn("No handler '{}' for channel {}; treating as direct route",
                    channel.getChannelHandler(), channel.getId());
            return ChannelResolution.direct();
        }

        // Извлечение текста выполняет control-api для всех handler'ов (generic делает JSON-фолбэк);
        // empty == «триггер не для этого канала» (фильтр) → доставку пропускаем.
        ChannelConfig cc = new ChannelConfig(
                channel.getAgentId(), channel.getConnectorCode(), channel.getIdentity(), channel.getConfig());
        Optional<InboundMessage> inbound = handler.handleInput(cc, trigger);
        if (inbound.isEmpty()) {
            return ChannelResolution.skip();
        }

        ChannelSession session = channelSessionService.findOrCreateActive(channel, null);
        ChannelInfo prompt = new ChannelInfo(channel.getId(), session.getId(), null);
        return ChannelResolution.channel(Channels.ofPrompt(prompt), inbound.get());
    }

    private static UUID declaredPromptChannelId(Trigger trigger) {
        if (trigger.context() == null || trigger.context().channels() == null
                || trigger.context().channels().prompt() == null) {
            return null;
        }
        return trigger.context().channels().prompt().channelId();
    }
}
