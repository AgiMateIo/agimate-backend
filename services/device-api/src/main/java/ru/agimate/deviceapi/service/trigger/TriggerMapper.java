package ru.agimate.deviceapi.service.trigger;

import lombok.experimental.UtilityClass;
import ru.agimate.deviceapi.database.entities.TriggerLog;
import ru.agimate.deviceapi.database.entities.TriggerLogAgent;

@UtilityClass
public class TriggerMapper {

    public static Trigger map(TriggerLogAgent triggerLogAgent) {
        TriggerLog triggerLog = triggerLogAgent.getTriggerLog();
        return new Trigger(
                triggerLog.getConnectorCode(),
                triggerLog.getIdentity(),
                triggerLog.getTriggerName(),
                triggerLogAgent.getPubId().toString(),
                triggerLog.getTriggerInput(),
                triggerLog.getOccurredAt() != null ? triggerLog.getOccurredAt().toString() : null,
                null
        );
    }
}
