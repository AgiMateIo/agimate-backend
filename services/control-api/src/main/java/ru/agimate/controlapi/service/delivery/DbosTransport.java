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
import ru.agimate.controlapi.database.entities.AgentRun;
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
    public void deliverTrigger(AgentRun agentRun, Trigger trigger, Channels channels, InboundMessage inbound) {
        DBOSClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("GENERIC delivery is not configured (dbos.enabled=false)");
        }
        Agent agent = agentRun.getAgent();
        String agentId = agent.getId().toString();
        String runId = agentRun.getId().toString();

        // Протокол v2: payload минимален — всё остальное (блоки, тулы, история, каналы)
        // воркер забирает одним GetRunContext(agent_id, trigger_id) по этому runId.
        WorkerRunMessage message = new WorkerRunMessage(agentId, runId);

        // Run-stage энкьюится сразу (роутера нет): workflow_id == runId, партиция — сессия
        // (single-writer-per-session — контрактное свойство очереди; direct-ран без сессии
        // получает собственную партицию по runId). Дедуп доставки — по workflow_id.
        String partitionKey = agentRun.getSessionId() != null
                ? agentRun.getSessionId().toString()
                : runId;
        DBOSClient.EnqueueOptions options = new DBOSClient.EnqueueOptions(
                WorkerProtocol.RUN_WORKFLOW,
                WorkerProtocol.RUN_CLASS,
                WorkerProtocol.RUN_QUEUE
        )
                .withInstanceName(WorkerProtocol.INSTANCE)
                .withSerialization(SerializationStrategy.PORTABLE)
                .withWorkflowId(runId)
                .withQueuePartitionKey(partitionKey);
        client.enqueueWorkflow(options, new Object[]{message});

        log.debug("run '{}' enqueued to DBOS queue '{}' for agent '{}' (partition={})",
                agentRun.getTriggerLog().getName(),
                WorkerProtocol.RUN_QUEUE,
                agentId,
                partitionKey);
    }

    /** Push не нужен: воркер сам забирает результат тулы поллингом {@code GetToolResult} по gRPC. */
    @Override
    public void deliverToolResult(Agent agent, IToolResult toolResult) {
        log.debug("tool result '{}' for agent '{}' awaits the worker's GetToolResult poll",
                toolResult.getId(), agent.getId());
    }
}
