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
 * The «how» layer of routing: for a recipient that has already been selected it decides how the
 * interaction is built — a channel (the one given in the trigger, or the active one for
 * {@code (agent, connector, connectionId)}) or direct delivery. Policy and audience (the «who») are
 * not part of this.
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
            // There is no prompt channel, but the producer may have declared a proactive reply channel
            // (progress/answer with no prompt) — time.due, for instance: a reminder with no incoming message.
            Channels proactive = resolveProactiveChannels(agent, trigger);
            return proactive != null ? ChannelResolution.channel(proactive, null) : ChannelResolution.direct();
        }
        return resolveInbound(channel, trigger);
    }

    /**
     * A proactive channel with no incoming message: the producer set channels in
     * {@link TriggerContext} with {@code progress}/{@code answer} but no {@code prompt}. The channel
     * must exist and belong to the agent (otherwise {@code null} → direct delivery), and the session is
     * re-resolved: the declared snapshot if it is still open, otherwise the channel's active session —
     * symmetrically to the outbound delivery fallback
     * ({@code ChannelMessageOutboundService.resolveSession}), so that the history, the partition and
     * the run's persisted row all point where the message will actually go.
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
        ChannelInfo resolved = new ChannelInfo(
                channel.getId(), resolveProactiveSessionId(channel, ref.sessionId()), ref.messageId());
        return new Channels(null,
                channels.progress() != null ? resolved : null,
                channels.answer() != null ? resolved : null);
    }

    /** The producer's snapshot session while it is open; otherwise the channel's active or new session, by the TTL heuristic. */
    private UUID resolveProactiveSessionId(Channel channel, UUID declaredSessionId) {
        if (declaredSessionId != null) {
            if (channelSessionService.findOpen(declaredSessionId, channel.getId()).isPresent()) {
                return declaredSessionId;
            }
            log.debug("Declared proactive session {} not open for channel {} - using active session",
                    declaredSessionId, channel.getId());
        }
        return channelSessionService.findOrCreateActive(channel, null).getId();
    }

    /**
     * The channel for an agent: if the producer set a prompt channel in {@link TriggerContext}
     * (declared), we take it — but only for the agent that owns the channel; otherwise we resolve it
     * per agent from the triple {@code (agent, connector, connectionId)}.
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
        UUID connectionId;
        try {
            connectionId = UUID.fromString(trigger.connectionId());
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
        return channelRepository.findByAgentIdAndConnectorCodeAndConnectionIdAndDeletedAtIsNull(
                agent.getId(), trigger.connectorCode(), connectionId).orElse(null);
    }

    private ChannelResolution resolveInbound(Channel channel, Trigger trigger) {
        ChannelHandler handler = channelHandlerRegistry.find(channel.getChannelHandler()).orElse(null);
        if (handler == null) {
            log.warn("No handler '{}' for channel {}; treating as direct route",
                    channel.getChannelHandler(), channel.getId());
            return ChannelResolution.direct();
        }

        // The channel's chat filtering (the «how» layer): the trigger's parameters must pass input_filter.
        if (!InputFilterEvaluator.matches(channel.getInputFilter(), trigger.data())) {
            log.debug("Trigger '{}' filtered out by channel {} input_filter", trigger.name(), channel.getId());
            return ChannelResolution.skip();
        }

        // Text extraction is done by control-api for every handler (generic falls back to JSON);
        // empty == «this trigger is not for this channel» (filtered) → we skip the delivery.
        ChannelConfig cc = new ChannelConfig(
                channel.getAgentId(), channel.getConnectorCode(), channel.getConnectionId().toString(), channel.getConfig());
        Optional<InboundMessage> inbound = handler.handleInput(cc, trigger);
        if (inbound.isEmpty()) {
            return ChannelResolution.skip();
        }

        ChannelSession session = resolveSession(channel, trigger);
        ChannelInfo info = new ChannelInfo(channel.getId(), session.getId(), null);
        // The progress role goes to the same channel when the handler delivers intermediate output (webchat);
        // answer is left unset — the worker falls back to prompt on its own.
        Channels channels = handler.deliverProgress(cc)
                ? new Channels(info, info, null)
                : Channels.ofPrompt(info);
        return ChannelResolution.channel(channels, inbound.get());
    }

    /**
     * The incoming message's session: the one declared by the producer in the prompt
     * {@link ChannelInfo} (webchat — the frontend chooses the session explicitly), if it is open and
     * belongs to the channel; otherwise the active or a new one, by the TTL heuristic.
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
