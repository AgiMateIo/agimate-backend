package ru.agimate.controlapi.service.channel.handler.dto;

import ru.agimate.controlapi.service.channel.handler.ChannelHandler;

import java.util.Map;
import java.util.UUID;

/**
 * Dispatch metadata of an answer into a channel — the companion of {@link OutboundMessage} in
 * {@link ChannelHandler#handleOutput}. Not content: control-api fills this in itself.
 *
 * @param messageId    effective id of the outgoing message — the reply tool's idempotency key
 * @param stream       the agent's output stream: {@code answer}/{@code progress}/{@code error};
 *                     null = answer (a message from the worker with no role)
 * @param progressType kind of the progress event ({@code THINKING}/{@code TOOL_CALL}/{@code TEXT});
 *                     null for non-progress streams
 * @param channelId    the channel the delivery is going to
 * @param sessionId    the channel's session, resolved at the service boundary
 * @param replyContext correlation of the inbound message, restored from the session (the answer's addressee)
 */
public record OutboundDispatch(
        String messageId,
        String stream,
        String progressType,
        UUID channelId,
        UUID sessionId,
        Map<String, Object> replyContext
) {
}
