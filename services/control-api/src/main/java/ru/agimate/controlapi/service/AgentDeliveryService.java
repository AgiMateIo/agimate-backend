package ru.agimate.controlapi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.entities.TriggerLogAgent;
import ru.agimate.controlapi.service.delivery.AgentDeliveryHandler;
import ru.agimate.controlapi.service.dto.IToolResult;
import ru.agimate.controlapi.service.trigger.ChannelContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AgentDeliveryService {

    private final Map<AgentType, AgentDeliveryHandler> handlers;
    private final AgentService agentService;

    public AgentDeliveryService(List<AgentDeliveryHandler> handlerList, AgentService agentService) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(AgentDeliveryHandler::getAgentType, Function.identity()));
        this.agentService = agentService;
    }

    public void deliverTrigger(Agent agent, TriggerLogAgent triggerLogAgent) {
        deliverTrigger(agent, triggerLogAgent, null);
    }

    public void deliverTrigger(Agent agent, TriggerLogAgent triggerLogAgent, ChannelContext channelContext) {
        try {
            handlers.get(agent.getType()).deliverTrigger(agent, triggerLogAgent, channelContext);
        } catch (Exception e) {
            triggerLogAgent.setError(e.getMessage());
            log.warn("Failed to send trigger '{}' to agent '{}' via {}: {}",
                    triggerLogAgent.getTriggerLog().getName(),
                    agent.getId(), agent.getType(), e.getMessage());
        }
    }

    public void deliverToolResult(UUID agentId, IToolResult toolResult) {
        Agent agent = agentService.findById(agentId);
        try {
            handlers.get(agent.getType()).deliverToolResult(agent, toolResult);
        } catch (Exception e) {
            log.warn("Failed to deliver tool result '{}' to agent '{}' via {}: {}",
                    toolResult.getId(), agent.getId(), agent.getType(), e.getMessage());
        }
    }
}
