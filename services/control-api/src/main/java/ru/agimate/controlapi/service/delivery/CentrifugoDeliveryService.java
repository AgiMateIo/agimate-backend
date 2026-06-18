package ru.agimate.controlapi.service.delivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.entities.TriggerLogAgent;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;
import ru.agimate.controlapi.service.dto.AgentMessage;
import ru.agimate.controlapi.service.dto.IToolResult;
import ru.agimate.controlapi.service.trigger.ChannelContext;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerMapper;

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
    public void deliverTrigger(Agent agent, TriggerLogAgent triggerLogAgent, ChannelContext channelContext) {
        String agentId = agent.getId().toString();
        Trigger trigger = TriggerMapper.map(triggerLogAgent);
        AgentMessage<Trigger> message = new AgentMessage<>(
                agentId, triggerLogAgent.getId().toString(), "trigger", channelContext, trigger);
        centrifugoService.publish(agentChannel(agent), message);
        log.debug("Trigger '{}' sent to agent '{}' via centrifugo",
                triggerLogAgent.getTriggerLog().getName(), agent.getId());
    }

    @Override
    public void deliverToolResult(Agent agent, IToolResult toolResult) {
        String agentId = agent.getId().toString();
        // todo: для доставки ответов тулов желательно использовать дургой канал агента. Другими словами, у агента два канала: 1 для получения заланий для агента, 2 для получения результатов вызова тулов (тут оборачивание в AgentMessage уже не требуется)
        AgentMessage<IToolResult> message = new AgentMessage<>(agentId, null, "toolResult", null, toolResult);
        centrifugoService.publish(agentChannel(agent), message);
        log.debug("Tool result '{}' sent to agent '{}' via centrifugo", toolResult.getId(), agent.getId());
    }

    private static String agentChannel(Agent agent) {
        return "agent:" + agent.getId();
    }
}
