package ru.agimate.controlapi.service.channel.handler.dto;

import ru.agimate.controlapi.service.channel.handler.ChannelHandler;

import java.util.List;
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
 * @param address      where in the channel the answer goes — the target's {@code ChannelInfo.address}
 *                     ({@link ChannelHandler#replyAddress}); an empty map when there is none
 * @param runId        the run whose output this is; {@code null} for a platform line with no run behind
 *                     it (the stop command's reply). The subagent channel needs it: its output is
 *                     the report of exactly that run
 * @param toolCallIds  ids of the tool calls a {@code TOOL_CALL} line names, in call order; empty on
 *                     every other line. The channel stores the ids with the message; an inline view
 *                     resolves its data from them (docs/decisions/connector-views.md)
 */
public record OutboundDispatch(
        String messageId,
        String stream,
        String progressType,
        UUID channelId,
        UUID sessionId,
        Map<String, Object> address,
        UUID runId,
        List<String> toolCallIds
) {

    public OutboundDispatch(String messageId, String stream, String progressType, UUID channelId,
                            UUID sessionId, Map<String, Object> address) {
        this(messageId, stream, progressType, channelId, sessionId, address, null, List.of());
    }

    public OutboundDispatch(String messageId, String stream, String progressType, UUID channelId,
                            UUID sessionId, Map<String, Object> address, UUID runId) {
        this(messageId, stream, progressType, channelId, sessionId, address, runId, List.of());
    }
}
