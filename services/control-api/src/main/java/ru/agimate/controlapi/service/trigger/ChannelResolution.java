package ru.agimate.controlapi.service.trigger;

import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;

/**
 * Как доставлять триггер агенту: {@code DIRECT} (прямая, не канальная), {@code CHANNEL}
 * (канальная — {@code channels}/{@code message} заполнены) или {@code SKIP} (handler отфильтровал).
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
