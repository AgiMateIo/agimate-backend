package ru.agimate.deviceapi.service.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.JsonUtils;
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
    private final TriggerDeliveryService triggerDeliveryService;

    @Async
    public void routeAppTrigger(App app, TriggerRequest triggerRequest) {
        Trigger trigger = Trigger.createBasic(
                app.getConnectorCode(),
                app.getPubId().toString(),
                triggerRequest.name(),
                JsonUtils.fromJsonToMap(triggerRequest.data().toString())
        );
        routeTrigger(app.getUserPubId(), trigger);
    }

    @Async
    public void routeWhTrigger(IntegrationCredentials integration, Trigger trigger) {
        routeTrigger(integration.getUserPubId(), trigger);
    }


    public void routeInternalTrigger(UUID userPubId, UUID agenticTeamPubId, Trigger trigger) {
        TriggerLog triggerLog = triggerLogService.createTriggerLog(userPubId, trigger);

        List<Agent> agents = agentTriggerPolicyService.findAllowedAgentsForTeamId(
                userPubId, agenticTeamPubId,  trigger.connectorCode(), trigger.identity(), trigger.name());

        for (Agent agent : agents) {
            TriggerLogAgent triggerLogAgent = TriggerLogAgent.builder()
                    .triggerLog(triggerLog)
                    .agent(agent)
                    .destination(agent.getTriggerDestination().name())
                    .build();

            triggerDeliveryService.fireTrigger(agent, triggerLogAgent);

            triggerLog.getTriggerLogAgents().add(triggerLogAgent);
        }
        triggerLogService.save(triggerLog);

    }

    private void routeTrigger(UUID userPubId, Trigger trigger) {
        TriggerLog triggerLog = triggerLogService.createTriggerLog(userPubId, trigger);

        List<Agent> agents = agentTriggerPolicyService.findAllowedAgents(
                userPubId, trigger.connectorCode(), trigger.identity(), trigger.name());

        for (Agent agent : agents) {
            TriggerLogAgent triggerLogAgent = TriggerLogAgent.builder()
                    .triggerLog(triggerLog)
                    .agent(agent)
                    .destination(agent.getTriggerDestination().name())
                    .build();

            triggerDeliveryService.fireTrigger(agent, triggerLogAgent);

            triggerLog.getTriggerLogAgents().add(triggerLogAgent);
        }
        triggerLogService.save(triggerLog);
    }

}
