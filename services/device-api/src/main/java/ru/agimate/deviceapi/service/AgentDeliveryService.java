package ru.agimate.deviceapi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.enums.AgentType;
import ru.agimate.deviceapi.database.entities.TriggerLogAgent;
import ru.agimate.deviceapi.service.delivery.AgentDeliveryHandler;
import ru.agimate.deviceapi.service.dto.IToolResult;
import ru.agimate.deviceapi.service.trigger.ChannelContext;

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
                    triggerLogAgent.getTriggerLog().getTriggerName(),
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
