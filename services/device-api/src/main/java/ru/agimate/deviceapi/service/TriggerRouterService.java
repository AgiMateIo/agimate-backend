package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.abac.AgentTriggerPolicyService;
import ru.agimate.deviceapi.controller.app.dto.TriggerRequest;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.entities.IntegrationCredentials;
import ru.agimate.deviceapi.database.entities.TriggerLog;
import ru.agimate.deviceapi.database.entities.TriggerLogAgent;
import ru.agimate.deviceapi.database.repositories.TriggerLogRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerRouterService {

    private final CentrifugoService centrifugoService;
    private final TriggerLogService triggerLogService;
    private final TriggerLogRepository triggerLogRepository;
    private final WebhookDeliveryService webhookDeliveryService;
    private final AgentTriggerPolicyService agentTriggerPolicyService;

    @Async
    public void routeAppTrigger(App app, TriggerRequest triggerRequest) {
        String connectorCode = app.getConnectorCode();
        String identity = app.getPubId().toString();
        UUID userPubId = app.getUserPubId();

        TriggerLog triggerLog = triggerLogService.createTriggerLog(userPubId, connectorCode, identity, triggerRequest);

        List<Agent> agents = agentTriggerPolicyService.findAllowedAgents(userPubId, connectorCode, identity, triggerRequest.name());

        for (Agent agent : agents) {
            TriggerLogAgent triggerLogAgent = TriggerLogAgent.builder()
                    .triggerLog(triggerLog)
                    .agent(agent)
                    .destination(agent.getTriggerDestination().name())
                    .build();
            try {
                switch (agent.getTriggerDestination()) {
                    case CENTRIFUGO -> routeToCentrifugo(agent, triggerRequest);
                    case WEBHOOK -> webhookDeliveryService.deliverWebhook(agent, triggerLogAgent, triggerRequest);
                }
            } catch (Exception e) {
                triggerLogAgent.setError(e.getMessage());
                log.warn("Failed to route trigger '{}' to agent '{}': {}", triggerRequest.name(), agent.getPubId(), e.getMessage());
            } finally {
                triggerLog.getTriggerLogAgents().add(triggerLogAgent);
            }
        }
        triggerLogRepository.save(triggerLog);
    }

    @Async
    public void routeWhTrigger(IntegrationCredentials integration, TriggerRequest triggerRequest) {
        // TODO: implement webhook trigger routing
    }

    private void routeToCentrifugo(Agent agent, TriggerRequest triggerRequest) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "trigger");
        payload.put("triggerName", triggerRequest.name());
        payload.put("triggerData", triggerRequest.data());
        payload.put("deviceId", triggerRequest.deviceId());
        payload.put("occurredAt", triggerRequest.occurredAt());

        String channel = "agent:" + agent.getPubId();
        centrifugoService.publishMessage(channel, payload);
        log.debug("Routed trigger '{}' to agent channel '{}'", triggerRequest.name(), channel);
    }
}
