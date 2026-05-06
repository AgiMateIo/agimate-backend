package ru.agimate.deviceapi.service.trigger;

import lombok.experimental.UtilityClass;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.TriggerLog;
import ru.agimate.deviceapi.database.entities.TriggerLogAgent;
import ru.agimate.deviceapi.service.dto.AgentEvent;

import java.time.Instant;
import java.time.ZoneOffset;

@UtilityClass
public class AgentEventMapper {

    public static AgentEvent from(Agent agent, TriggerLogAgent triggerLogAgent) {
        TriggerLog triggerLog = triggerLogAgent.getTriggerLog();
        Instant occurredAt = triggerLog.getOccurredAt() != null
                ? triggerLog.getOccurredAt().toInstant(ZoneOffset.UTC)
                : Instant.now();
        return new AgentEvent(
                triggerLog.getPubId() + ":" + agent.getPubId(),
                agent.getPubId().toString(),
                triggerLog.getConnectorCode() + "." + triggerLog.getTriggerName(),
                occurredAt,
                triggerLog.getTriggerInput()
        );
    }
}
