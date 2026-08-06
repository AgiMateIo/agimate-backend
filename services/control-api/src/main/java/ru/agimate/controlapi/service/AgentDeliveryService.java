package ru.agimate.controlapi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.entities.AgentRun;
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

    // We depend on the repository rather than on AgentService: delivery is a low-level mechanism and needs only
    // an agent lookup. Injecting the high-level AgentService would close a bean cycle
    public AgentDeliveryService(List<AgentTransport> transportList, AgentRepository agentRepository) {
        this.transports = transportList.stream()
                .collect(Collectors.toMap(AgentTransport::getAgentType, Function.identity()));
        this.agentRepository = agentRepository;
    }

    /**
     * Whether anything can be pushed to the agent — see {@link AgentTransport#supportsPush()}. Asked
     * before a run is created: pushing is the only reason to create one.
     */
    public boolean supportsPush(Agent agent) {
        return supportsPush(agent.getType());
    }

    public boolean supportsPush(AgentType type) {
        AgentTransport transport = transports.get(type);
        return transport != null && transport.supportsPush();
    }

    public void deliverTrigger(AgentRun agentRun, Trigger trigger, Channels channels, InboundMessage inbound) {
        Agent agent = agentRun.getAgent();
        try {
            transports.get(agent.getType()).deliverTrigger(agentRun, trigger, channels, inbound);
        } catch (Exception e) {
            agentRun.setError(e.getMessage());
            log.warn("Failed to send trigger '{}' to agent '{}' via {}: {}",
                    agentRun.getTriggerLog().getName(),
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
