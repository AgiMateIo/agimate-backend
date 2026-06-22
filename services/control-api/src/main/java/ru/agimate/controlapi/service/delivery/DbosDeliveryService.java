package ru.agimate.controlapi.service.delivery;

import dev.dbos.transact.DBOSClient;
import dev.dbos.transact.workflow.SerializationStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ru.agimate.controlapi.config.DbosProperties;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.entities.TriggerLogAgent;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.dto.AgentMessage;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.Trigger;

@Slf4j
@Service
@RequiredArgsConstructor
public class DbosDeliveryService implements AgentDeliveryHandler {

    private final ObjectProvider<DBOSClient> clientProvider;
    private final DbosProperties props;

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

        DbosProperties.Workflow workflow = props.getWorkflows().getAgentWorkflow();
        String type = "trigger";
        String runId = triggerLogAgent.getId().toString();
        AgentMessage<Trigger> message = new AgentMessage<>(agentId, runId, type, channels, inbound, trigger);

        DBOSClient.EnqueueOptions options = new DBOSClient.EnqueueOptions(
                workflow.getName(),
                workflow.getClassName(),
                workflow.getQueueName()
        )
                .withInstanceName(workflow.getInstanceName())
                .withSerialization(SerializationStrategy.PORTABLE)
                .withWorkflowId(runId)
                .withQueuePartitionKey(agentId);
        client.enqueueWorkflow(options, new Object[]{message});

        log.debug("{} '{}' enqueued to DBOS queue '{}' for agent '{}'",
                type,
                triggerLogAgent.getTriggerLog().getName(),
                workflow.getQueueName(),
                agentId);
    }
}
