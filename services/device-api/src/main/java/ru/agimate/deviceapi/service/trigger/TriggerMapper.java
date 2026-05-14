package ru.agimate.deviceapi.service.trigger;

import lombok.experimental.UtilityClass;
import ru.agimate.deviceapi.database.entities.TriggerLog;
import ru.agimate.deviceapi.database.entities.TriggerLogAgent;

import java.util.LinkedHashMap;
import java.util.Map;

@UtilityClass
public class TriggerMapper {

    public static Trigger map(TriggerLogAgent triggerLogAgent) {
        return map(triggerLogAgent, null);
    }

    public static Trigger map(TriggerLogAgent triggerLogAgent, ChannelContext channelContext) {
        TriggerLog triggerLog = triggerLogAgent.getTriggerLog();
        return new Trigger(
                triggerLog.getConnectorCode(),
                triggerLog.getIdentity(),
                triggerLog.getTriggerName(),
                triggerLogAgent.getPubId().toString(),
                withChannel(triggerLog.getTriggerInput(), channelContext),
                triggerLog.getOccurredAt() != null ? triggerLog.getOccurredAt().toString() : null,
                null
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
