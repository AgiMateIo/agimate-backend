package ru.agimate.agentworker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One channel endpoint: where a message is read from or written to. Mirrors control-api's
 * {@code ChannelInfo}; {@code channelId}/{@code sessionId} are UUIDs on the wire but kept as
 * opaque strings here. The {@code prompt} channel's {@code sessionId} is the
 * single-writer-per-session key.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChannelInfo(String channelId, String sessionId, String messageId) {
}
