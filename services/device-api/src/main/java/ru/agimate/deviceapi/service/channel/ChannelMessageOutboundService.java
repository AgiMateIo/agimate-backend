package ru.agimate.deviceapi.service.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.abac.AccessEffect;
import ru.agimate.deviceapi.database.entities.Channel;
import ru.agimate.deviceapi.database.entities.ChannelSession;
import ru.agimate.deviceapi.database.entities.ChannelSessionMessage;
import ru.agimate.deviceapi.database.entities.MessageDirection;
import ru.agimate.deviceapi.database.entities.ToolUseLog;
import ru.agimate.deviceapi.database.repositories.ChannelRepository;
import ru.agimate.deviceapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.deviceapi.database.repositories.ChannelSessionRepository;
import ru.agimate.deviceapi.database.repositories.ToolUseLogRepository;
import ru.agimate.deviceapi.service.ConnectorService;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelMessageOutboundService {

    private final ChannelRepository channelRepository;
    private final ChannelSessionRepository channelSessionRepository;
    private final ChannelSessionMessageRepository channelSessionMessageRepository;
    private final ChannelSessionService channelSessionService;
    private final ToolUseLogRepository toolUseLogRepository;
    private final ConnectorService connectorService;

    public record OutboundResult(ChannelSession session, ChannelSessionMessage message, ToolUseLog toolUseLog) {}

    @Transactional
    public OutboundResult send(UUID agentPubId, UUID channelPubId, UUID sessionPubIdOrNull,
                               String text, String toolCallId) {
        Channel channel = channelRepository.findByPubIdAndDeletedAtIsNull(channelPubId)
                .orElseThrow(() -> new NotFoundStatusException("Channel not found"));

        if (!agentPubId.equals(channel.getAgentPubId())) {
            throw new NotFoundStatusException("Channel not found for this agent");
        }

        ChannelSession session = resolveSession(channel, sessionPubIdOrNull);
        Map<String, Object> triggerInput = lookupLastInboundTrigger(session);

        Map<String, Object> renderedParams = PlaceholderRenderer.render(
                channel.getReplyToolParams(), text, triggerInput);

        ChannelSessionMessage outMessage = ChannelSessionMessage.builder()
                .sessionId(session.getId())
                .direction(MessageDirection.OUT)
                .message(text != null ? text : "")
                .build();
        ChannelSessionMessage savedMessage = channelSessionMessageRepository.save(outMessage);
        channelSessionService.bumpLastMessageAt(session);

        ToolUseLog toolUseLog = upsertToolUseLog(channel, toolCallId, renderedParams);
        connectorService.pushToConnector(toolUseLog);

        log.info("Sent OUT message pubId={} session={} channel={} via tool={}",
                savedMessage.getPubId(), session.getPubId(), channel.getPubId(), channel.getReplyToolName());
        return new OutboundResult(session, savedMessage, toolUseLog);
    }

    private ChannelSession resolveSession(Channel channel, UUID sessionPubIdOrNull) {
        if (sessionPubIdOrNull != null) {
            ChannelSession session = channelSessionRepository.findByPubId(sessionPubIdOrNull)
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
                .findFirstBySessionIdAndDirectionOrderByCreatedAtDesc(session.getId(), MessageDirection.IN)
                .map(ChannelSessionMessage::getTriggerInput)
                .orElse(Map.of());
    }

    private ToolUseLog upsertToolUseLog(Channel channel, String toolCallId, Map<String, Object> renderedParams) {
        String effectiveToolCallId = toolCallId != null && !toolCallId.isBlank()
                ? toolCallId : UUID.randomUUID().toString();

        Optional<ToolUseLog> existing = toolUseLogRepository
                .findByToolUseIdAndAgentPubId(effectiveToolCallId, channel.getAgentPubId());
        if (existing.isPresent()) {
            return existing.get();
        }

        ToolUseLog log = ToolUseLog.builder()
                .agentPubId(channel.getAgentPubId())
                .userPubId(channel.getUserPubId())
                .connectorCode(channel.getReplyConnectorCode())
                .identity(channel.getReplyIdentity())
                .toolUseId(effectiveToolCallId)
                .toolName(channel.getReplyToolName())
                .input(renderedParams)
                .accessEffect(AccessEffect.ALLOW)
                .build();
        return toolUseLogRepository.save(log);
    }
}
