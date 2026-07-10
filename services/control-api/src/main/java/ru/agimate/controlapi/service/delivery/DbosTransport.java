package ru.agimate.controlapi.service.delivery;

import dev.dbos.transact.DBOSClient;
import dev.dbos.transact.workflow.SerializationStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ru.agimate.agentworker.WorkerProtocol;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.entities.TriggerLogAgent;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.dto.IToolResult;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.Trigger;

@Slf4j
@Service
@RequiredArgsConstructor
public class DbosTransport implements AgentTransport {

    private final ObjectProvider<DBOSClient> clientProvider;

    @Override
    public AgentType getAgentType() {
        return AgentType.GENERIC;
    }

    @Override
    public void deliverTrigger(TriggerLogAgent triggerLogAgent, Trigger trigger, Channels channels, InboundMessage inbound) {
        DBOSClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("GENERIC delivery is not configured (dbos.enabled=false)");
        }
        Agent agent = triggerLogAgent.getAgent();
        String agentId = agent.getId().toString();
        String runId = triggerLogAgent.getId().toString();

        // Протокол v2: payload минимален — всё остальное (блоки, тулы, история, каналы)
        // воркер забирает одним GetRunContext(agent_id, trigger_id) по этому runId.
        WorkerRunMessage message = new WorkerRunMessage(agentId, runId);

        // Contract names and the router-id scheme come from the shared WorkerProtocol (compiled
        // into both services): the router gets a derived id so the bare runId is free for the
        // run-stage workflow (run_id == its DBOS workflow id).
        DBOSClient.EnqueueOptions options = new DBOSClient.EnqueueOptions(
                WorkerProtocol.AGENT_WORKFLOW,
                WorkerProtocol.AGENT_CLASS,
                WorkerProtocol.AGENT_QUEUE
        )
                .withInstanceName(WorkerProtocol.INSTANCE)
                .withSerialization(SerializationStrategy.PORTABLE)
                .withWorkflowId(WorkerProtocol.routerWorkflowId(runId))
                .withQueuePartitionKey(agentId);
        client.enqueueWorkflow(options, new Object[]{message});

        log.debug("run '{}' enqueued to DBOS queue '{}' for agent '{}'",
                triggerLogAgent.getTriggerLog().getName(),
                WorkerProtocol.AGENT_QUEUE,
                agentId);
    }

    /** Push не нужен: воркер сам забирает результат тулы поллингом {@code GetToolResult} по gRPC. */
    @Override
    public void deliverToolResult(Agent agent, IToolResult toolResult) {
        log.debug("tool result '{}' for agent '{}' awaits the worker's GetToolResult poll",
                toolResult.getId(), agent.getId());
    }
}
