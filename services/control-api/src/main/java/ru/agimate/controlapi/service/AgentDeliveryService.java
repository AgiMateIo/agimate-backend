package ru.agimate.controlapi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.entities.TriggerLogAgent;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.delivery.AgentTransport;
import ru.agimate.controlapi.service.dto.IToolResult;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AgentDeliveryService {

    private final Map<AgentType, AgentTransport> transports;
    private final AgentRepository agentRepository;

    // Зависим от репозитория, а не от AgentService: доставка — низкоуровневый механизм, ему нужен
    // лишь lookup агента. Инъекция высокоуровневого AgentService замыкала цикл бинов
    // (ConnectorRegistry → telegram → triggerRouter → delivery → AgentService → ConnectorRegistry).
    public AgentDeliveryService(List<AgentTransport> transportList, AgentRepository agentRepository) {
        this.transports = transportList.stream()
                .collect(Collectors.toMap(AgentTransport::getAgentType, Function.identity()));
        this.agentRepository = agentRepository;
    }

    public void deliverTrigger(TriggerLogAgent triggerLogAgent, Trigger trigger, Channels channels, InboundMessage inbound) {
        Agent agent = triggerLogAgent.getAgent();
        try {
            transports.get(agent.getType()).deliverTrigger(triggerLogAgent, trigger, channels, inbound);
        } catch (Exception e) {
            triggerLogAgent.setError(e.getMessage());
            log.warn("Failed to send trigger '{}' to agent '{}' via {}: {}",
                    triggerLogAgent.getTriggerLog().getName(),
                    agent.getId(), agent.getType(), e.getMessage());
        }
    }

    public void deliverToolResult(UUID agentId, IToolResult toolResult) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        try {
            transports.get(agent.getType()).deliverToolResult(agent, toolResult);
        } catch (Exception e) {
            log.warn("Failed to deliver tool result '{}' to agent '{}' via {}: {}",
                    toolResult.getId(), agent.getId(), agent.getType(), e.getMessage());
        }
    }
}
