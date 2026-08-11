package ru.agimate.controlapi.service.trigger;

import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;

/**
 * How to deliver a trigger to an agent: {@code DIRECT} (directly, outside a channel), {@code CHANNEL}
 * (through a channel — {@code channels}/{@code message} are populated), {@code SKIP} (the handler
 * filtered it out) or {@code CANCEL} (the message is the stop command, addressed to the platform
 * rather than to the agent).
 */
record ChannelResolution(Kind kind, Channels channels, InboundMessage message) {

    enum Kind { DIRECT, CHANNEL, SKIP, CANCEL }

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

    /**
     * The stop command. No run is created for it — hence no message either: the command never enters
     * the agent's context, it was not addressed to the agent. {@code sessionId} inside {@code channels}
     * is null when the channel has no live session, and then there is simply nothing to stop.
     */
    static ChannelResolution cancel(Channels channels) {
        return new ChannelResolution(Kind.CANCEL, channels, null);
    }
}
