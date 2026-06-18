package ru.agimate.controlapi.service.trigger;

import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.database.entities.TriggerLog;
import ru.agimate.controlapi.database.entities.TriggerLogAgent;

@UtilityClass
public class TriggerMapper {

    public static Trigger map(TriggerLogAgent triggerLogAgent) {
        TriggerLog triggerLog = triggerLogAgent.getTriggerLog();
        return new Trigger(
                triggerLog.getConnectorCode(),
                triggerLog.getIdentity(),
                triggerLog.getName(),
                triggerLogAgent.getId().toString(),
                triggerLog.getInput(),
                triggerLog.getOccurredAt() != null ? triggerLog.getOccurredAt().toString() : null,
                null
        );
    }
}
