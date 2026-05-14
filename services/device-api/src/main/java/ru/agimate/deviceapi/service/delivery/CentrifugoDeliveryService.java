package ru.agimate.deviceapi.service.delivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.AgentType;
import ru.agimate.deviceapi.database.entities.TriggerLogAgent;
import ru.agimate.deviceapi.service.centrifugo.CentrifugoService;
import ru.agimate.deviceapi.service.dto.IToolResult;
import ru.agimate.deviceapi.service.trigger.ChannelContext;
import ru.agimate.deviceapi.service.trigger.TriggerMapper;

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
        centrifugoService.publishMessage(agentChannel(agent), "trigger",
                TriggerMapper.map(triggerLogAgent.getTriggerLog(), channelContext));
        log.debug("Trigger '{}' sent to agent '{}' via centrifugo",
                triggerLogAgent.getTriggerLog().getTriggerName(), agent.getPubId());
    }

    @Override
    public void deliverToolResult(Agent agent, IToolResult toolResult) {
        centrifugoService.publishMessage(agentChannel(agent), "toolResult", toolResult);
        log.debug("Tool result '{}' sent to agent '{}' via centrifugo", toolResult.getId(), agent.getPubId());
    }

    private static String agentChannel(Agent agent) {
        return "agent:" + agent.getPubId();
    }
}
