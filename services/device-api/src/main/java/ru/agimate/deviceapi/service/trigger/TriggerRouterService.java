package ru.agimate.deviceapi.service.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.abac.AgentTriggerPolicyService;
import ru.agimate.deviceapi.controller.app.dto.TriggerRequest;
import ru.agimate.deviceapi.database.entities.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerRouterService {

    private final TriggerLogService triggerLogService;
    private final AgentTriggerPolicyService agentTriggerPolicyService;
    private final TriggerEmitterService triggerEmitterService;

    @Async
    public void routeAppTrigger(App app, TriggerRequest triggerRequest) {
        routeTrigger(app.getUserPubId(), app.getConnectorCode(), app.getPubId().toString(), triggerRequest);
    }

    @Async
    public void routeWhTrigger(IntegrationCredentials integration, TriggerRequest triggerRequest) {
        routeTrigger(integration.getUserPubId(), integration.getConnectorCode(), integration.getPubId().toString(), triggerRequest);
    }

    private void routeTrigger(UUID userPubId, String connectorCode, String identity, TriggerRequest triggerRequest) {
        TriggerLog triggerLog = triggerLogService.createTriggerLog(userPubId, connectorCode, identity, triggerRequest);

        List<Agent> agents = agentTriggerPolicyService.findAllowedAgents(userPubId, connectorCode, identity, triggerRequest.name());


        for (Agent agent : agents) {
            TriggerLogAgent triggerLogAgent = TriggerLogAgent.builder()
                    .triggerLog(triggerLog)
                    .agent(agent)
                    .destination(agent.getTriggerDestination().name())
                    .build();

            Trigger trigger = new Trigger(
                    triggerLog.getConnectorCode(),
                    triggerLog.getIdentity(),
                    triggerLog.getTriggerId(),
                    triggerLog.getTriggerName(),
                    triggerLog.getTriggerInput(),
                    triggerLog.getOccurredAt() == null ? "" : triggerLog.getOccurredAt().toString()
            );

            triggerEmitterService.fireTrigger(agent, trigger);

            triggerLog.getTriggerLogAgents().add(triggerLogAgent);
        }
        triggerLogService.save(triggerLog);
    }



}
