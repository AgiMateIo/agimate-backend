package ru.agimate.deviceapi.service.trigger;

import lombok.experimental.UtilityClass;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.TriggerLog;
import ru.agimate.deviceapi.database.entities.TriggerLogAgent;
import ru.agimate.deviceapi.service.dto.AgentEvent;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@UtilityClass
public class AgentEventMapper {

    public static AgentEvent from(Agent agent, TriggerLogAgent triggerLogAgent) {
        return from(agent, triggerLogAgent, null);
    }

    public static AgentEvent from(Agent agent, TriggerLogAgent triggerLogAgent, ChannelContext channelContext) {
        TriggerLog triggerLog = triggerLogAgent.getTriggerLog();
        Instant occurredAt = triggerLog.getOccurredAt() != null
                ? triggerLog.getOccurredAt().toInstant(ZoneOffset.UTC)
                : Instant.now();
        return new AgentEvent(
                triggerLog.getPubId() + ":" + agent.getPubId(),
                agent.getPubId().toString(),
                triggerLog.getConnectorCode() + "." + triggerLog.getTriggerName(),
                occurredAt,
                withChannel(triggerLog.getTriggerInput(), channelContext)
        );
    }

    private static Map<String, Object> withChannel(Map<String, Object> data, ChannelContext channelContext) {
        if (channelContext == null) {
            return data;
        }
        Map<String, Object> enriched = new LinkedHashMap<>(data != null ? data : Map.of());
        enriched.put("_channel_id", channelContext.channelPubId().toString());
        enriched.put("_channel_session_id", channelContext.channelSessionPubId().toString());
        return enriched;
    }
}
