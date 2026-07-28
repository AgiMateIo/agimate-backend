package ru.agimate.controlapi.service.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;
import ru.agimate.controlapi.service.tool.AgentToolCallService;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.ChannelSession;
import ru.agimate.controlapi.database.entities.ChannelSessionMessage;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.database.repositories.ChannelSessionRepository;
import ru.agimate.controlapi.service.channel.handler.dto.ChannelConfig;
import ru.agimate.controlapi.service.channel.handler.ChannelHandler;
import ru.agimate.controlapi.service.channel.handler.ChannelHandlerRegistry;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundDispatch;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;

import java.util.List;
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
    private final OutboundAttachmentParser attachmentParser;

    public record OutboundResult(ChannelSession session, String messageId) {}

    /**
     * Deliberately NOT {@code @Transactional}: every step commits its own transaction (creating the
     * session, the tool log), and the execution dispatch comes afterwards — otherwise the async
     * executor would not see an uncommitted {@code tool_call_log}, and a call from a post-commit
     * context (SaveMessage) would silently lose the record (the notorious REQUIRED inside afterCommit).
     */
    public OutboundResult send(UUID agentId, UUID channelId, UUID sessionIdOrNull,
                               OutboundMessage outbound, String messageId, String stream,
                               String progressType) {
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

        // The attach convention: [[attach:agf_…]] markers from the text → parts (the owner is the channel's user).
        OutboundMessage effectiveOutbound = attachmentParser.parse(channel.getUserId(), outbound);
        if (!effectiveOutbound.parts().isEmpty() && !handler.supportsOutboundAttachments()) {
            log.warn("Channel {} handler={} does not support outbound attachments — dropping {} part(s)",
                    channel.getId(), handler.name(), effectiveOutbound.parts().size());
            effectiveOutbound = new OutboundMessage(effectiveOutbound.text(), List.of());
        }
        // Only the answer stream carries attachments: a marker mentioned in progress text announces a future
        // answer, and delivering in both places would duplicate the file (the streams have different message_ids).
        boolean answerStream = stream == null
                || ChannelSessionMessageKind.ANSWER.name().equalsIgnoreCase(stream);
        if (!effectiveOutbound.parts().isEmpty() && !answerStream) {
            effectiveOutbound = new OutboundMessage(effectiveOutbound.text(), List.of());
        }

        // Resolve the idempotency key once so it is both used by the dispatcher and returned to the worker.
        String effectiveMessageId = messageId != null && !messageId.isBlank()
                ? messageId : UUID.randomUUID().toString();

        ChannelConfig config = new ChannelConfig(
                channel.getAgentId(), channel.getConnectorCode(), channel.getConnectionId().toString(), channel.getConfig());
        OutboundDispatch dispatch = new OutboundDispatch(
                effectiveMessageId, stream, progressType, channel.getId(), session.getId(), replyContext);

        dispatchAll(channel, handler.handleOutput(config, effectiveOutbound, dispatch));

        log.info("Dispatched OUT message session={} channel={} via handler={}",
                session.getId(), channel.getId(), handler.name());
        return new OutboundResult(session, effectiveMessageId);
    }

    /**
     * The requests are independent (the text and each attachment are separate messages), so one
     * failure does not sink the rest: attachments are best-effort (as is dropping unresolvable markers
     * in the parser), and delivered text matters more. A total failure is rethrown — there was no
     * delivery at all, and the caller's retry is safe: the keys are idempotent and repeating a
     * successful request is a replay.
     */
    private void dispatchAll(Channel channel, List<ToolCallRequest> requests) {
        RuntimeException firstFailure = null;
        int failed = 0;
        for (ToolCallRequest request : requests) {
            try {
                agentToolCallService.processToolCall(channel.getAgentId(), request);
            } catch (RuntimeException e) {
                failed++;
                if (firstFailure == null) {
                    firstFailure = e;
                }
                log.warn("Failed to dispatch outbound request {} (tool={}) to channel {}: {}",
                        request.getId(), request.getName(), channel.getId(), e.getMessage());
            }
        }
        if (firstFailure != null && failed == requests.size()) {
            throw firstFailure;
        }
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
