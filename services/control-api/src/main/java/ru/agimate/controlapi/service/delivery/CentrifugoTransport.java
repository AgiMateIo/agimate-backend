package ru.agimate.controlapi.service.delivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.realtime.RealtimeEvent.AgentDelivery;
import ru.agimate.controlapi.realtime.RealtimePublisher;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.dto.AgentMessage;
import ru.agimate.controlapi.service.dto.IToolResult;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.Trigger;

@Slf4j
@Service
@RequiredArgsConstructor
public class CentrifugoTransport implements AgentTransport {

    private final RealtimePublisher realtime;

    @Override
    public AgentType getAgentType() {
        return AgentType.CENTRIFUGO;
    }

    @Override
    public void deliverTrigger(AgentRun agentRun, Trigger trigger, Channels channels, InboundMessage inbound) {
        Agent agent = agentRun.getAgent();
        String agentId = agent.getId().toString();
        String type = channels != null ? "channel_message" : "trigger";
        String sessionId = agentRun.getSessionId().toString();
        AgentMessage<Trigger> message = new AgentMessage<>(
                agentId, agentRun.getId().toString(), type, sessionId, channels, inbound, trigger);
        realtime.publish(new AgentDelivery(agent.getId(), message));
        log.debug("Trigger '{}' sent to agent '{}' via centrifugo",
                agentRun.getTriggerLog().getName(), agent.getId());
    }

    @Override
    public void deliverToolResult(Agent agent, IToolResult toolResult) {
        String agentId = agent.getId().toString();
        // todo: tool results would be better delivered over a separate agent channel. In other words the agent
        // would have two channels: one for receiving its own tasks, another for receiving tool call results (there
        // the AgentMessage wrapper is no longer needed)
        AgentMessage<IToolResult> message = new AgentMessage<>(agentId, null, "toolResult", null, null, null, toolResult);
        realtime.publish(new AgentDelivery(agent.getId(), message));
        log.debug("Tool result '{}' sent to agent '{}' via centrifugo", toolResult.getId(), agent.getId());
    }

}
