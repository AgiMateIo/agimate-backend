package ru.agimate.deviceapi.service.dto;

import ru.agimate.deviceapi.service.trigger.ChannelContext;

public record AgentMessage<T>(
        String agentId,
        String runId,
        String type,
        ChannelContext channelContext,
        T payload
) {
}
