package ru.agimate.controlapi.service.delivery;

import org.slf4j.LoggerFactory;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.entities.TriggerLogAgent;
import ru.agimate.controlapi.service.dto.IToolResult;
import ru.agimate.controlapi.service.trigger.ChannelContext;

public interface AgentDeliveryHandler {

    AgentType getAgentType();

    void deliverTrigger(Agent agent, TriggerLogAgent triggerLogAgent, ChannelContext channelContext);

    default void deliverToolResult(Agent agent, IToolResult toolResult) {
        LoggerFactory.getLogger(getClass()).warn(
                "Tool result '{}' not delivered to agent '{}' via {} - handler does not support tool results",
                toolResult.getId(), agent.getId(), getAgentType());
    }
}
