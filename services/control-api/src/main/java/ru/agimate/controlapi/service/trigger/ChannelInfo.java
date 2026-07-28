package ru.agimate.controlapi.service.trigger;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * A reference to a channel in a particular role (prompt/progress/answer).
 *
 * @param channelId the channel the interaction goes through
 * @param sessionId the channel's active session; for prompt its id is written into {@code AgentRun.sessionId}
 * @param messageId id of the incoming or target message in the channel (threads, replies); not populated yet
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChannelInfo(UUID channelId, UUID sessionId, String messageId) {
}
