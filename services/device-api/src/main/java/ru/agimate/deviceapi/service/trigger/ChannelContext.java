package ru.agimate.deviceapi.service.trigger;

import java.util.UUID;

public record ChannelContext(UUID channelPubId, UUID channelSessionPubId) {
}
