package ru.agimate.controlapi.service.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.service.tool.AgentToolCallService;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.ChannelSession;
import ru.agimate.controlapi.database.entities.ChannelSessionMessage;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.database.repositories.ChannelSessionRepository;
import ru.agimate.controlapi.service.channel.handler.dto.ChannelConfig;
import ru.agimate.controlapi.service.channel.handler.ChannelHandler;
import ru.agimate.controlapi.service.channel.handler.ChannelHandlerRegistry;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelMessageOutboundService {

    private final ChannelRepository channelRepository;
    private final ChannelSessionRepository channelSessionRepository;
    private final ChannelSessionMessageRepository channelSessionMessageRepository;
    private final ChannelSessionService channelSessionService;
    private final ChannelHandlerRegistry channelHandlerRegistry;
    private final AgentToolCallService agentToolCallService;

    public record OutboundResult(ChannelSession session, String messageId) {}

    @Transactional
    public OutboundResult send(UUID agentId, UUID channelId, UUID sessionIdOrNull,
                               String text, String messageId) {
        Channel channel = channelRepository.findByIdAndDeletedAtIsNull(channelId)
                .orElseThrow(() -> new NotFoundStatusException("Channel not found"));

        if (!agentId.equals(channel.getAgentId())) {
            throw new NotFoundStatusException("Channel not found for this agent");
        }

        ChannelHandler handler = channelHandlerRegistry.find(channel.getChannelHandler())
                .orElseThrow(() -> new NotFoundStatusException(
                        "Channel handler not found: " + channel.getChannelHandler()));

        ChannelSession session = resolveSession(channel, sessionIdOrNull);
        Map<String, Object> replyContext = lookupLastInboundTrigger(session);

        // Resolve the idempotency key once so it is both used by the dispatcher and returned to the worker.
        String effectiveMessageId = messageId != null && !messageId.isBlank()
                ? messageId : UUID.randomUUID().toString();

        ChannelConfig config = new ChannelConfig(
                channel.getAgentId(), channel.getConnectorCode(), channel.getIdentity(), channel.getConfig());
        OutboundMessage outbound = OutboundMessage.text(text, replyContext, effectiveMessageId);

        handler.handleOutput(config, outbound, agentToolCallService);

        log.info("Dispatched OUT message session={} channel={} via handler={}",
                session.getId(), channel.getId(), handler.name());
        return new OutboundResult(session, effectiveMessageId);
    }

    private ChannelSession resolveSession(Channel channel, UUID sessionIdOrNull) {
        if (sessionIdOrNull != null) {
            ChannelSession session = channelSessionRepository.findById(sessionIdOrNull)
                    .orElseThrow(() -> new NotFoundStatusException("Channel session not found"));
            if (!session.getChannelId().equals(channel.getId())) {
                throw new NotFoundStatusException("Channel session does not belong to this channel");
            }
            return session;
        }
        return channelSessionService.findOrCreateActive(channel, null);
    }

    private Map<String, Object> lookupLastInboundTrigger(ChannelSession session) {
        return channelSessionMessageRepository
                .findFirstBySessionIdAndTriggerInputIsNotNullOrderByCreatedAtDesc(session.getId())
                .map(ChannelSessionMessage::getTriggerInput)
                .orElse(Map.of());
    }
}
