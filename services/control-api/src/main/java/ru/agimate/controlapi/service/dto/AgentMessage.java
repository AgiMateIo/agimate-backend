package ru.agimate.controlapi.service.dto;

import ru.agimate.controlapi.service.trigger.ChannelContext;

public record AgentMessage<T>(
        String agentId,
        String runId,
        String type,
        ChannelContext channelContext,
        T payload
) {
}
