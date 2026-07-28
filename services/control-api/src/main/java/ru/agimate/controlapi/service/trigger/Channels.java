package ru.agimate.controlapi.service.trigger;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The channels of an agent's interaction within a single run. {@code prompt} is the inbound channel
 * (where the trigger came from); {@code progress} is filled with the same channel when its handler
 * delivers intermediate output ({@code ChannelHandler.deliverProgress}, webchat); {@code answer} is
 * not populated yet — the worker falls back to {@code prompt}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Channels(ChannelInfo prompt, ChannelInfo progress, ChannelInfo answer) {

    public static Channels ofPrompt(ChannelInfo prompt) {
        return new Channels(prompt, null, null);
    }
}
