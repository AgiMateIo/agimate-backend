package ru.agimate.deviceapi.service;

import dev.dbos.transact.DBOSClient;
import dev.dbos.transact.workflow.SerializationStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.config.DbosProperties;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.TriggerLogAgent;
import ru.agimate.deviceapi.service.dto.AgentMessage;
import ru.agimate.deviceapi.service.trigger.ChannelContext;
import ru.agimate.deviceapi.service.trigger.Trigger;
import ru.agimate.deviceapi.service.trigger.TriggerMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class DbosDeliveryService {

    private final ObjectProvider<DBOSClient> clientProvider;
    private final DbosProperties props;

    public void deliverTrigger(Agent agent, TriggerLogAgent triggerLogAgent) {
        deliverTrigger(agent, triggerLogAgent, null);
    }

    public void deliverTrigger(Agent agent, TriggerLogAgent triggerLogAgent, ChannelContext channelContext) {
        DBOSClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("GENERIC delivery is not configured (dbos.enabled=false)");
        }
        String agentId = agent.getPubId().toString();
        Trigger trigger = TriggerMapper.map(triggerLogAgent.getTriggerLog());
        AgentMessage<Trigger> message = new AgentMessage<>(agentId, "trigger", channelContext, trigger);
        DBOSClient.EnqueueOptions options = new DBOSClient.EnqueueOptions(
                props.getWorkflow().getName(),
                null, //props.getWorkflow().getClassName(),
                props.getQueue().getName()
        )
                .withSerialization(SerializationStrategy.PORTABLE)
                .withQueuePartitionKey(agentId);
        client.enqueueWorkflow(options, new Object[]{message});
        log.debug("Trigger '{}' enqueued to DBOS queue '{}' for agent '{}'",
                triggerLogAgent.getTriggerLog().getTriggerName(),
                props.getQueue().getName(),
                agentId);
    }
}
