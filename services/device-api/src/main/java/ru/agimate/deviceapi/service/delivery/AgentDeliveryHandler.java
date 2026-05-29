package ru.agimate.deviceapi.service.delivery;

import org.slf4j.LoggerFactory;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.enums.AgentType;
import ru.agimate.deviceapi.database.entities.TriggerLogAgent;
import ru.agimate.deviceapi.service.dto.IToolResult;
import ru.agimate.deviceapi.service.trigger.ChannelContext;

public interface AgentDeliveryHandler {

    AgentType getAgentType();

    void deliverTrigger(Agent agent, TriggerLogAgent triggerLogAgent, ChannelContext channelContext);

    default void deliverToolResult(Agent agent, IToolResult toolResult) {
        LoggerFactory.getLogger(getClass()).warn(
                "Tool result '{}' not delivered to agent '{}' via {} - handler does not support tool results",
                toolResult.getId(), agent.getPubId(), getAgentType());
    }
}
