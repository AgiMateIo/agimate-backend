package ru.agimate.controlapi.service.channel.handler.dto;

import ru.agimate.controlapi.service.channel.handler.ChannelHandler;

import java.util.Map;
import java.util.UUID;

/**
 * Descriptor of a channel, passed into a {@link ChannelHandler}.
 *
 * <p>{@code agentId}, {@code connectorCode} and {@code connectionId} are first-class (separate
 * {@code channels} columns). {@code agentId} is the channel's owner; {@code handleOutput} dispatches
 * the reply tool as that agent (using someone else's channel is impossible — ownership is checked at
 * the service boundary). By {@code connectorCode}/{@code connectionId} the handler calls the tools and
 * reads the triggers of the right connector. {@code settings} are the arbitrary settings of a
 * particular handler (which deserialises them itself).
 */
public record ChannelConfig(
        UUID agentId,
        String connectorCode,
        String connectionId,
        Map<String, Object> settings
) {

    public Object setting(String key) {
        return settings == null ? null : settings.get(key);
    }
}
