package ru.agimate.controlapi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.ToolCallLogRepository;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.delivery.AgentTransport;
import ru.agimate.controlapi.service.delivery.DetachedToolResultDelivery;
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
    private final ToolCallLogRepository toolCallLogRepository;
    private final DetachedToolResultDelivery detachedDelivery;

    // We depend on the repository rather than on AgentService: delivery is a low-level mechanism and needs only
    // an agent lookup. Injecting the high-level AgentService would close a bean cycle
    public AgentDeliveryService(List<AgentTransport> transportList, AgentRepository agentRepository,
                                ToolCallLogRepository toolCallLogRepository,
                                DetachedToolResultDelivery detachedDelivery) {
        this.transports = transportList.stream()
                .collect(Collectors.toMap(AgentTransport::getAgentType, Function.identity()));
        this.agentRepository = agentRepository;
        this.toolCallLogRepository = toolCallLogRepository;
        this.detachedDelivery = detachedDelivery;
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

    /**
     * The single funnel for a finished tool call, whoever executed it (the backend's pool or an
     * app posting the result back). A detached call is the fork: the worker no longer waits, so the
     * result re-enters the agent as a {@code tool_completed} trigger — a new run of the call's
     * session, absorbed by the running main through steering or executed on its own. A call nobody
     * detached goes to the agent-type transport as before.
     */
    public void deliverToolResult(ToolCallLog toolCallLog, IToolResult toolResult) {
        // The caller's entity may predate the detach: executeTool holds the row it created before
        // the execution began, and the worker stamps detached_at mid-execution. The ownership
        // decision must read the current row, not a snapshot from before the tool started.
        toolCallLog = toolCallLogRepository.findById(toolCallLog.getId()).orElse(toolCallLog);
        Agent agent = agentRepository.findById(toolCallLog.getAgentId())
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        try {
            if (toolCallLog.getDetachedAt() == null) {
                transports.get(agent.getType()).deliverToolResult(agent, toolResult);
                return;
            }
            if (!supportsPush(agent)) {
                log.info("detached call {}: agent {} has no push transport, result stays in the log",
                        toolResult.getId(), agent.getId());
                return;
            }
            detachedDelivery.prepare(toolCallLog, toolResult).ifPresent(prepared ->
                    deliverTrigger(prepared.run(), prepared.trigger(), prepared.channels(), null));
        } catch (Exception e) {
            log.warn("Failed to deliver tool result '{}' to agent '{}' via {}: {}",
                    toolResult.getId(), agent.getId(), agent.getType(), e.getMessage());
        }
    }
}
