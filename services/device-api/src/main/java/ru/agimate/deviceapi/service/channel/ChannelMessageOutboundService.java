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

    public record OutboundResult(ChannelSession session, ToolUseLog toolUseLog) {}

    @Transactional
    public OutboundResult send(UUID agentId, UUID channelId, UUID sessionIdOrNull,
                               String text, String toolCallId) {
        Channel channel = channelRepository.findByIdAndDeletedAtIsNull(channelId)
                .orElseThrow(() -> new NotFoundStatusException("Channel not found"));

        if (!agentId.equals(channel.getAgentId())) {
            throw new NotFoundStatusException("Channel not found for this agent");
        }

        ChannelSession session = resolveSession(channel, sessionIdOrNull);
        Map<String, Object> triggerInput = lookupLastInboundTrigger(session);

        Map<String, Object> renderedParams = PlaceholderRenderer.render(
                channel.getReplyToolParams(), text, triggerInput);

        ToolUseLog toolUseLog = upsertToolUseLog(channel, toolCallId, renderedParams);
        connectorService.pushToConnector(toolUseLog);

        log.info("Dispatched OUT message session={} channel={} via tool={}",
                session.getId(), channel.getId(), channel.getReplyToolName());
        return new OutboundResult(session, toolUseLog);
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

    private ToolUseLog upsertToolUseLog(Channel channel, String toolCallId, Map<String, Object> renderedParams) {
        String effectiveToolCallId = toolCallId != null && !toolCallId.isBlank()
                ? toolCallId : UUID.randomUUID().toString();

        Optional<ToolUseLog> existing = toolUseLogRepository
                .findByToolUseIdAndAgentId(effectiveToolCallId, channel.getAgentId());
        if (existing.isPresent()) {
            return existing.get();
        }

        ToolUseLog log = ToolUseLog.builder()
                .agentId(channel.getAgentId())
                .userId(channel.getUserId())
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
