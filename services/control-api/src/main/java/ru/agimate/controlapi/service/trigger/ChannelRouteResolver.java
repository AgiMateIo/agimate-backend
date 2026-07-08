package ru.agimate.controlapi.service.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.ChannelSession;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.service.channel.ChannelSessionService;
import ru.agimate.controlapi.service.channel.InputFilterEvaluator;
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
            // Нет prompt-канала, но продюсер мог объявить проактивный канал ответа (progress/answer
            // без prompt) — например time.due: напоминание без входящего сообщения.
            Channels proactive = resolveProactiveChannels(agent, trigger);
            return proactive != null ? ChannelResolution.channel(proactive, null) : ChannelResolution.direct();
        }
        return resolveInbound(channel, trigger);
    }

    /**
     * Проактивный канал без входящего: продюсер задал в {@link TriggerContext} channels с
     * {@code progress}/{@code answer}, но без {@code prompt}. Отдаём объявленные channels как есть,
     * проверив, что канал существует и принадлежит агенту; иначе {@code null} → прямая доставка.
     */
    private Channels resolveProactiveChannels(Agent agent, Trigger trigger) {
        if (trigger.context() == null || trigger.context().channels() == null) {
            return null;
        }
        Channels channels = trigger.context().channels();
        if (channels.prompt() != null) {
            return null;
        }
        ChannelInfo ref = channels.progress() != null ? channels.progress() : channels.answer();
        if (ref == null || ref.channelId() == null) {
            return null;
        }
        Channel channel = channelRepository.findById(ref.channelId())
                .filter(c -> c.getDeletedAt() == null)
                .orElse(null);
        if (channel == null || !channel.getAgentId().equals(agent.getId())) {
            log.debug("Declared proactive channel {} not applicable to agent {} - direct route",
                    ref.channelId(), agent.getId());
            return null;
        }
        return channels;
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

        // Chat-filtering канала (слой «как»): параметры триггера должны проходить input_filter.
        if (!InputFilterEvaluator.matches(channel.getInputFilter(), trigger.data())) {
            log.debug("Trigger '{}' filtered out by channel {} input_filter", trigger.name(), channel.getId());
            return ChannelResolution.skip();
        }

        // Извлечение текста выполняет control-api для всех handler'ов (generic делает JSON-фолбэк);
        // empty == «триггер не для этого канала» (фильтр) → доставку пропускаем.
        ChannelConfig cc = new ChannelConfig(
                channel.getAgentId(), channel.getConnectorCode(), channel.getIdentity(), channel.getConfig());
        Optional<InboundMessage> inbound = handler.handleInput(cc, trigger);
        if (inbound.isEmpty()) {
            return ChannelResolution.skip();
        }

        ChannelSession session = resolveSession(channel, trigger);
        ChannelInfo info = new ChannelInfo(channel.getId(), session.getId(), null);
        // progress-роль тем же каналом, если handler доставляет промежуточный вывод (webchat);
        // answer не заполняем — worker сам фолбэчится на prompt.
        Channels channels = handler.deliverProgress(cc)
                ? new Channels(info, info, null)
                : Channels.ofPrompt(info);
        return ChannelResolution.channel(channels, inbound.get());
    }

    /**
     * Сессия входящего: объявленная продюсером в prompt-{@link ChannelInfo} (webchat — фронт выбирает
     * сессию явно), если она открыта и принадлежит каналу; иначе активная/новая по TTL-эвристике.
     */
    private ChannelSession resolveSession(Channel channel, Trigger trigger) {
        UUID declaredSessionId = declaredPromptSessionId(trigger);
        if (declaredSessionId != null) {
            Optional<ChannelSession> declared = channelSessionService.findOpen(declaredSessionId, channel.getId());
            if (declared.isPresent()) {
                return declared.get();
            }
            log.warn("Declared session {} not open for channel {} - falling back to active session",
                    declaredSessionId, channel.getId());
        }
        return channelSessionService.findOrCreateActive(channel, null);
    }

    private static UUID declaredPromptChannelId(Trigger trigger) {
        ChannelInfo prompt = declaredPrompt(trigger);
        return prompt != null ? prompt.channelId() : null;
    }

    private static UUID declaredPromptSessionId(Trigger trigger) {
        ChannelInfo prompt = declaredPrompt(trigger);
        return prompt != null ? prompt.sessionId() : null;
    }

    private static ChannelInfo declaredPrompt(Trigger trigger) {
        if (trigger.context() == null || trigger.context().channels() == null) {
            return null;
        }
        return trigger.context().channels().prompt();
    }
}
