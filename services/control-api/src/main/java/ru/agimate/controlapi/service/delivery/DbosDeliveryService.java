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
import ru.agimate.controlapi.service.dto.AgentMessage;
import ru.agimate.controlapi.service.trigger.ChannelContext;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerMapper;

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
    public void deliverTrigger(Agent agent, TriggerLogAgent triggerLogAgent, ChannelContext channelContext) {
        DBOSClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("GENERIC delivery is not configured (dbos.enabled=false)");
        }
        String agentId = agent.getId().toString();
        Trigger trigger = TriggerMapper.map(triggerLogAgent);

        DbosProperties.Workflow workflow = props.getWorkflows().getAgentWorkflow();
        String type;
        if (channelContext != null) {
            type = "channel_message";
        } else {
            type = "trigger";
        }
        String runId = triggerLogAgent.getId().toString();
        AgentMessage<Trigger> message = new AgentMessage<>(agentId, runId, type, channelContext, trigger);

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
                triggerLogAgent.getTriggerLog().getTriggerName(),
                workflow.getQueueName(),
                agentId);
    }
}
