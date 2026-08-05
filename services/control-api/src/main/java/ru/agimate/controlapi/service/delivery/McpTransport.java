package ru.agimate.controlapi.service.delivery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.dto.IToolResult;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.Trigger;

/**
 * The «no push» transport. Stateless MCP has no server→client channel at all, so nothing can be
 * delivered to an MCP agent — it comes for its tools itself. The bean exists even though it delivers
 * nothing: delivery is a lookup by type in {@link ru.agimate.controlapi.service.AgentDeliveryService},
 * and a missing transport would surface as an NPE on the first deferred result rather than as a log
 * line. Triggers do not reach it — {@code TriggerRouterService} drops non-pushable agents before a
 * run is created; anything that gets here (a scheduled job's result, a late tool result) has nowhere
 * to go and is dropped deliberately.
 */
@Slf4j
@Service
public class McpTransport implements AgentTransport {

    @Override
    public AgentType getAgentType() {
        return AgentType.MCP;
    }

    @Override
    public boolean supportsPush() {
        return false;
    }

    @Override
    public void deliverTrigger(AgentRun agentRun, Trigger trigger, Channels channels, InboundMessage inbound) {
        log.warn("Trigger '{}' dropped for MCP agent {}: the protocol has no server-initiated channel",
                agentRun.getTriggerLog().getName(), agentRun.getAgent().getId());
    }

    @Override
    public void deliverToolResult(Agent agent, IToolResult toolResult) {
        log.warn("Tool result '{}' dropped for MCP agent {}: it is delivered in the tools/call response",
                toolResult.getId(), agent.getId());
    }
}
