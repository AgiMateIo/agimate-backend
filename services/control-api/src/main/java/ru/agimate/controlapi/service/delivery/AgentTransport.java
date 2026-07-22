package ru.agimate.controlapi.service.delivery;

import org.slf4j.LoggerFactory;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.dto.IToolResult;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.Trigger;

public interface AgentTransport {

    AgentType getAgentType();

    /**
     * Доставляет триггер агенту. {@code agent} берётся из {@code agentRun.getAgent()}.
     * {@code channels}/{@code inbound} заданы для канальных триггеров, {@code null} — для прямых.
     */
    void deliverTrigger(AgentRun agentRun, Trigger trigger, Channels channels, InboundMessage inbound);

    default void deliverToolResult(Agent agent, IToolResult toolResult) {
        LoggerFactory.getLogger(getClass()).warn(
                "Tool result '{}' not delivered to agent '{}' via {} - handler does not support tool results",
                toolResult.getId(), agent.getId(), getAgentType());
    }
}
