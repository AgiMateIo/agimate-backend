package ru.agimate.controlapi.service.trigger;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;
import java.util.UUID;

/**
 * A reference to a channel in a particular role (prompt/progress/answer).
 *
 * @param channelId the channel the interaction goes through
 * @param sessionId the channel's active session; for prompt its id is written into {@code AgentRun.sessionId}
 * @param messageId id of the incoming or target message in the channel (threads, replies); not populated yet
 * @param address   where in the channel the answer goes, as its handler reads it (Telegram: {@code chatId});
 *                  {@code null} for channels that address by session. Part of the snapshot so that a copy
 *                  of it — an event run in the same conversation — answers to the same place
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChannelInfo(UUID channelId, UUID sessionId, String messageId, Map<String, Object> address) {

    public ChannelInfo {
        address = address == null || address.isEmpty() ? null : address;
    }

    public ChannelInfo(UUID channelId, UUID sessionId, String messageId) {
        this(channelId, sessionId, messageId, null);
    }
}
