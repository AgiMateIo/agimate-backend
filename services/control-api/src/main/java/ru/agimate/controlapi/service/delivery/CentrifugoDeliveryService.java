package ru.agimate.controlapi.service.delivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.entities.TriggerLogAgent;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.dto.AgentMessage;
import ru.agimate.controlapi.service.dto.IToolResult;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.Trigger;

@Slf4j
@Service
@RequiredArgsConstructor
public class CentrifugoDeliveryService implements AgentDeliveryHandler {

    private final CentrifugoService centrifugoService;

    @Override
    public AgentType getAgentType() {
        return AgentType.CENTRIFUGO;
    }

    @Override
    public void deliverTrigger(TriggerLogAgent triggerLogAgent, Trigger trigger, Channels channels, InboundMessage inbound) {
        Agent agent = triggerLogAgent.getAgent();
        String agentId = agent.getId().toString();
        String type = channels != null ? "channel_message" : "trigger";
        String sessionId = triggerLogAgent.getSessionId() != null ? triggerLogAgent.getSessionId().toString() : null;
        AgentMessage<Trigger> message = new AgentMessage<>(
                agentId, triggerLogAgent.getId().toString(), type, sessionId, channels, inbound, trigger);
        centrifugoService.publish(agentChannel(agent), message);
        log.debug("Trigger '{}' sent to agent '{}' via centrifugo",
                triggerLogAgent.getTriggerLog().getName(), agent.getId());
    }

    @Override
    public void deliverToolResult(Agent agent, IToolResult toolResult) {
        String agentId = agent.getId().toString();
        // todo: для доставки ответов тулов желательно использовать дургой канал агента. Другими словами, у агента два канала: 1 для получения заланий для агента, 2 для получения результатов вызова тулов (тут оборачивание в AgentMessage уже не требуется)
        AgentMessage<IToolResult> message = new AgentMessage<>(agentId, null, "toolResult", null, null, null, toolResult);
        centrifugoService.publish(agentChannel(agent), message);
        log.debug("Tool result '{}' sent to agent '{}' via centrifugo", toolResult.getId(), agent.getId());
    }

    private static String agentChannel(Agent agent) {
        return "agent:" + agent.getId();
    }
}
