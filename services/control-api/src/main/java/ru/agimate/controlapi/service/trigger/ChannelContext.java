package ru.agimate.controlapi.service.trigger;

import java.util.UUID;

public record ChannelContext(
        UUID channelId,
        UUID channelSessionId,
        String channelName,
        String triggerMessageField
) {
}
