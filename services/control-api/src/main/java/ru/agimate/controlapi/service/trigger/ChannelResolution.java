package ru.agimate.controlapi.service.trigger;

import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;

/**
 * How to deliver a trigger to an agent: {@code DIRECT} (directly, outside a channel), {@code CHANNEL}
 * (through a channel — {@code channels}/{@code message} are populated) or {@code SKIP} (the handler
 * filtered it out).
 */
record ChannelResolution(Kind kind, Channels channels, InboundMessage message) {

    enum Kind { DIRECT, CHANNEL, SKIP }

    private static final ChannelResolution DIRECT = new ChannelResolution(Kind.DIRECT, null, null);
    private static final ChannelResolution SKIP = new ChannelResolution(Kind.SKIP, null, null);

    static ChannelResolution direct() {
        return DIRECT;
    }

    static ChannelResolution skip() {
        return SKIP;
    }

    static ChannelResolution channel(Channels channels, InboundMessage message) {
        return new ChannelResolution(Kind.CHANNEL, channels, message);
    }
}
