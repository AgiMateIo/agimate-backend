package ru.agimate.agentworker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Routing endpoints by role. {@code prompt} is the input channel a message arrived on (also the
 * channel-vs-trigger discriminator). {@code progress}/{@code answer} are for streaming progress
 * and the final reply (may be absent until control-api populates them).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Channels(ChannelInfo prompt, ChannelInfo progress, ChannelInfo answer) {
}
