package ru.agimate.deviceapi.service.trigger;

import lombok.experimental.UtilityClass;
import ru.agimate.deviceapi.database.entities.TriggerLog;

@UtilityClass
public class TriggerMapper {

    public static Trigger map(TriggerLog triggerLog) {
        return new Trigger(
                triggerLog.getConnectorCode(),
                triggerLog.getIdentity(),
                triggerLog.getTriggerId(),
                triggerLog.getTriggerName(),
                triggerLog.getTriggerInput(),
                triggerLog.getOccurredAt() != null ? triggerLog.getOccurredAt().toString() : null
        );
    }
}
